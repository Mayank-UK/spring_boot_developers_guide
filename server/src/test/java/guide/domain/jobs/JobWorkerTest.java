package guide.domain.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.result.Result;
import guide.data.jobs.InMemoryJobQueue;
import guide.data.jobs.RecordingEmailSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The worker's state machine under a hand-driven clock: enqueue → claim →
 * fail ×N → succeed, the exact backoff schedule between attempts, the
 * dead-letter ending, and replay — every timestamp constructed, zero
 * sleeps. This is the deterministic-seam pattern the topic teaches about
 * its own demo: runOnce(now) takes time as a parameter, so the suite IS
 * the ticker.
 */
class JobWorkerTest {

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private final InMemoryJobQueue queue = new InMemoryJobQueue();
    private final RecordingEmailSender sender = new RecordingEmailSender(CLOCK);

    private JobWorker worker(JobQueue queue) {
        return new JobWorker(queue,
                List.of(new WelcomeEmailJobHandler(sender, newJsonMapper()),
                        new GenerateReportJobHandler(), new CleanupPiiJobHandler()),
                () -> 0.0);
    }

    private static tools.jackson.databind.ObjectMapper newJsonMapper() {
        return tools.jackson.databind.json.JsonMapper.builder().build();
    }

    private JobRecord enqueue(String type, String payload, int failAttempts) {
        EnqueueJob enqueueJob = new EnqueueJob(queue, CLOCK, newJsonMapper());
        Result<JobRecord> result = enqueueJob.execute(
                new EnqueueJob.Request(type, payload, failAttempts));
        assertTrue(result.isSuccess(), "valid enqueue must succeed");
        return ((guide.core.result.Success<JobRecord>) result).value();
    }

    @Test
    void fullLifecycleFailTwiceThenSucceedOnTheExactBackoffSchedule() {
        JobRecord job = enqueue("send-welcome-email",
                "{\"to\":\"reader@example.com\",\"name\":\"Reader\"}", 2);
        JobWorker worker = worker(queue);

        // attempt 1 at T0: fails, retry due at T0 + 1s
        List<JobRecord> touched = worker.runOnce(T0);
        assertEquals(1, touched.size());
        JobRecord afterFirst = queue.find(job.id()).orElseThrow();
        assertEquals(JobRecord.JobState.FAILED, afterFirst.state());
        assertEquals(1, afterFirst.attempts().size());
        assertEquals(JobRecord.Attempt.Outcome.FAILED, afterFirst.attempts().getFirst().outcome());
        assertEquals(T0.plusSeconds(1), afterFirst.nextAttemptAt());
        assertEquals(T0, afterFirst.startedAt());
        assertEquals(0, sender.sent().size(), "a failed attempt sends nothing");

        // half a second later: still not due — the backoff is a promise, not a hint
        assertEquals(0, worker.runOnce(T0.plusMillis(500)).size());

        // attempt 2 at T0+1s: fails, retry due at T0+1s + 2s
        worker.runOnce(T0.plusSeconds(1));
        JobRecord afterSecond = queue.find(job.id()).orElseThrow();
        assertEquals(JobRecord.JobState.FAILED, afterSecond.state());
        assertEquals(2, afterSecond.attempts().size());
        assertEquals(T0.plusSeconds(3), afterSecond.nextAttemptAt());

        // attempt 3 at T0+3s: injection spent — the mail finally leaves
        worker.runOnce(T0.plusSeconds(3));
        JobRecord done = queue.find(job.id()).orElseThrow();
        assertEquals(JobRecord.JobState.SUCCEEDED, done.state());
        assertEquals(3, done.attempts().size());
        assertEquals(JobRecord.Attempt.Outcome.SUCCEEDED, done.attempts().getLast().outcome());
        assertEquals(T0.plusSeconds(3), done.finishedAt());
        assertNull(done.nextAttemptAt());
        assertEquals(0, done.attemptsLeft());
        assertEquals(1, sender.sent().size(), "one succeeded job, one welcome mail");
        assertEquals("reader@example.com", sender.sent().getFirst().to());
        assertTrue(sender.sent().getFirst().subject().contains("Welcome"));
    }

    @Test
    void budgetExhaustedLandsInTheDeadLetterQueueWithTheErrorTrail() {
        JobRecord job = enqueue("send-welcome-email", "{\"to\":\"a@b.c\"}", 9);
        JobWorker worker = worker(queue);

        worker.runOnce(T0);
        worker.runOnce(T0.plusSeconds(1));
        worker.runOnce(T0.plusSeconds(3));

        JobRecord dead = queue.find(job.id()).orElseThrow();
        assertEquals(JobRecord.JobState.DEAD, dead.state());
        assertEquals(3, dead.attempts().size());
        assertEquals(JobRecord.Attempt.Outcome.DEAD, dead.attempts().getLast().outcome());
        assertTrue(dead.lastError().contains("injected failure #3"),
                "the killing error is the evidence: " + dead.lastError());
        assertEquals(T0.plusSeconds(3), dead.finishedAt());
        assertNull(dead.nextAttemptAt());
        assertEquals(List.of(dead), queue.deadLetters());
        assertEquals(0, sender.sent().size());
    }

    @Test
    void replayEnqueuesAFreshLineageAndTheDeadRecordStaysAsEvidence() {
        JobRecord job = enqueue("send-welcome-email", "{\"to\":\"a@b.c\"}", 9);
        JobWorker worker = worker(queue);
        worker.runOnce(T0);
        worker.runOnce(T0.plusSeconds(1));
        worker.runOnce(T0.plusSeconds(3));
        assertEquals(JobRecord.JobState.DEAD, queue.find(job.id()).orElseThrow().state());

        // replaying WITHOUT a fix re-poisons: same injection, walks right back
        ReplayDeadLetter replay = new ReplayDeadLetter(queue, CLOCK);
        Result<JobRecord> sameFix = replay.execute(job.id(), new ReplayDeadLetter.Request(9));
        assertTrue(sameFix.isSuccess());
        JobRecord replayed = ((guide.core.result.Success<JobRecord>) sameFix).value();
        assertEquals(1, replayed.replays());
        assertEquals(0, replayed.attempts().size());
        assertEquals(JobRecord.JobState.QUEUED, replayed.state());
        assertEquals(1, queue.deadLetters().size(), "the dead record stays in the DLQ");

        // replaying WITH the fix (injection cleared) succeeds on the next pass
        Result<JobRecord> fixed = replay.execute(job.id(), new ReplayDeadLetter.Request(0));
        assertTrue(fixed.isSuccess());
        worker.runOnce(T0.plusSeconds(10));
        JobRecord fixedJob = queue.find(
                ((guide.core.result.Success<JobRecord>) fixed).value().id()).orElseThrow();
        assertEquals(JobRecord.JobState.SUCCEEDED, fixedJob.state());
        assertEquals(1, sender.sent().size());
    }

    @Test
    void replayRefusesAnythingNotDead() {
        JobRecord job = enqueue("generate-report", "{}", 0);
        Result<JobRecord> refused = new ReplayDeadLetter(queue, CLOCK)
                .execute(job.id(), new ReplayDeadLetter.Request(0));
        assertTrue(refused instanceof guide.core.result.Failed<JobRecord> failed
                && failed.failure().kind() == guide.core.errors.FailureKind.CONFLICT,
                "replaying a queued job is a conflict, not a retry");
    }

    @Test
    void aRunClaimOnlyEarliestDueWorkUpToTheBatchCeiling() {
        enqueue("generate-report", "{}", 0); // due at T0
        enqueue("cleanup-pii", "{}", 0);     // due at T0
        // a third job, made due later than the first two
        JobRecord later = enqueue("generate-report", "{}", 0);
        queue.save(later.awaitingRetry(new JobRecord.Attempt(1, T0,
                JobRecord.Attempt.Outcome.FAILED, "stale"), T0.plusSeconds(30)));

        JobWorker worker = worker(queue);
        List<JobRecord> touched = worker.runOnce(T0);
        assertEquals(2, touched.size(), "only the two jobs due by T0 run");
        assertEquals(JobRecord.JobState.SUCCEEDED, touched.get(0).state());
        assertEquals(JobRecord.JobState.SUCCEEDED, touched.get(1).state());
        assertEquals(JobRecord.JobState.FAILED,
                queue.find(later.id()).orElseThrow().state());
    }

    @Test
    void theBatchCeilingBoundsOnePass() {
        for (int i = 0; i < 12; i++) {
            enqueue("cleanup-pii", "{}", 0);
        }
        List<JobRecord> touched = worker(queue).runOnce(T0);
        assertEquals(8, touched.size(), "TICK_BATCH caps one pass at eight claims");
        assertEquals(4, queue.snapshot().stream()
                .filter(job -> job.state() == JobRecord.JobState.QUEUED).count());
    }

    @Test
    void aTypeWithNoHandlerIsADeadLetterNotARetry() {
        JobWorker handlerless = new JobWorker(queue, List.of(), () -> 0.0);
        JobRecord job = enqueue("cleanup-pii", "{}", 0);
        handlerless.runOnce(T0);
        JobRecord dead = queue.find(job.id()).orElseThrow();
        assertEquals(JobRecord.JobState.DEAD, dead.state());
        assertTrue(dead.lastError().contains("no handler registered"));
    }

    @Test
    void duplicateHandlersForOneTypeAreARefusedWiringBug() {
        assertThrows(IllegalStateException.class, () -> new JobWorker(queue,
                List.of(new CleanupPiiJobHandler(), new CleanupPiiJobHandler()), () -> 0.0));
    }

    @Test
    void enqueueValidationRejectsWhatNobodyCouldExecute() {
        EnqueueJob enqueueJob = new EnqueueJob(queue, CLOCK, newJsonMapper());
        assertTrue(enqueueJob.execute(new EnqueueJob.Request("render-video", "{}", 0))
                instanceof guide.core.result.Failed<JobRecord>, "unknown type");
        assertTrue(enqueueJob.execute(new EnqueueJob.Request("generate-report", "not json", 0))
                instanceof guide.core.result.Failed<JobRecord>, "payload must be JSON");
        assertTrue(enqueueJob.execute(new EnqueueJob.Request("generate-report", "{}", -1))
                instanceof guide.core.result.Failed<JobRecord>, "negative injection");
        assertTrue(enqueueJob.execute(null)
                instanceof guide.core.result.Failed<JobRecord>, "missing body shape");
    }

    @Test
    void theOverviewAnswersTheTwoObservabilityNumbers() {
        enqueue("generate-report", "{}", 0);    // succeeds at T0
        enqueue("send-welcome-email", "{}", 9); // fails, retry pending
        worker(queue).runOnce(T0);
        enqueue("send-welcome-email", "{}", 0); // enqueued after the pass: stays queued

        ReadQueueOverview overview = new ReadQueueOverview(queue,
                Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC));
        ReadQueueOverview.Overview view = overview.execute();
        assertEquals(1L, view.countsByState().get("succeeded"));
        assertEquals(1L, view.countsByState().get("failed"));
        assertEquals(1L, view.countsByState().get("queued"));
        assertEquals(2, view.depth(), "depth counts everything not terminal");
        assertEquals(5000L, view.oldestPendingAgeMs(), "fixed clock ⇒ exact age");
        assertNotNull(view.oldestPendingId());
    }
}
