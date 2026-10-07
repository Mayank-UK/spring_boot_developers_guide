package guide.domain.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Topic 29's worker: the loop that turns queued records into outcomes.
 * Policy lives here and nowhere else — how many claims one pass may make
 * (a bounded batch, so a full queue cannot wedge a tick), what a failure
 * costs (the backoff curve plus jitter), and when the budget runs out (the
 * dead-letter decision). The work itself hides behind the {@link JobHandler}
 * port, one implementation per {@link JobType}.
 *
 * The testability seams are the topic's backbone: {@link #runOnce(Instant)}
 * takes {@code now} EXPLICITLY instead of reading a clock — the @Scheduled
 * ticker passes the wall clock, tests pass constructed instants, and
 * neither sleeps. The worker never owns a timer; time is a parameter.
 *
 * Failure semantics: a handler that throws fails the attempt. If attempts
 * remain, the record goes back as FAILED with nextAttemptAt = now +
 * jittered backoff; when the budget is spent the SAME failure lands the
 * record in DEAD — the dead-letter queue — with the killing attempt
 * recorded as outcome "dead". Nothing is retried automatically after that;
 * replay is an operator's deliberate act.
 */
public final class JobWorker {

    /** How many due jobs one pass may claim — the batch ceiling that keeps a tick bounded. */
    static final int TICK_BATCH = 8;

    private final JobQueue queue;
    private final Map<JobType, JobHandler> handlers;
    private final DoubleSupplier jitter;

    public JobWorker(JobQueue queue, List<JobHandler> handlers, DoubleSupplier jitter) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.handlers = new EnumMap<>(JobType.class);
        for (JobHandler handler : Objects.requireNonNull(handlers, "handlers")) {
            JobType type = handler.type();
            if (this.handlers.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("two handlers registered for " + type.wireName());
            }
        }
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    /**
     * One pass: claim due work until the batch ceiling or an empty moment,
     * execute each claim, persist its outcome. Returns the records it
     * touched (empty when the queue is idle) so callers and tests assert
     * on the effect of one pass without re-querying.
     */
    public List<JobRecord> runOnce(Instant now) {
        Objects.requireNonNull(now, "now");
        List<JobRecord> touched = new ArrayList<>();
        for (int claims = 0; claims < TICK_BATCH; claims++) {
            Optional<JobRecord> claimed = queue.claimNextDue(now);
            if (claimed.isEmpty()) {
                break;
            }
            touched.add(execute(claimed.get(), now));
        }
        return List.copyOf(touched);
    }

    private JobRecord execute(JobRecord claimed, Instant now) {
        // publish the in-flight state first: a crash mid-handler leaves a
        // RUNNING record as evidence, not an invisible limbo
        JobRecord running = claimed.running(now);
        queue.save(running);
        int attemptNumber = claimed.attempts().size() + 1;
        JobHandler handler = handlers.get(claimed.type());
        if (handler == null) {
            // wiring bug, not a flaky dependency — no retry could fix it
            JobRecord dead = running.dead(new JobRecord.Attempt(attemptNumber, now,
                    JobRecord.Attempt.Outcome.DEAD,
                    "no handler registered for " + claimed.type().wireName()));
            queue.save(dead);
            return dead;
        }
        try {
            handler.execute(running);
            JobRecord done = running.succeeded(new JobRecord.Attempt(attemptNumber, now,
                    JobRecord.Attempt.Outcome.SUCCEEDED, null));
            queue.save(done);
            return done;
        } catch (Exception failure) {
            String error = errorOf(failure);
            if (attemptNumber >= claimed.maxAttempts()) {
                JobRecord dead = running.dead(new JobRecord.Attempt(attemptNumber, now,
                        JobRecord.Attempt.Outcome.DEAD, error));
                queue.save(dead);
                return dead;
            }
            Instant nextDue = now.plus(JobBackoff.withJitter(
                    JobBackoff.delayAfter(attemptNumber), jitter));
            JobRecord retrying = running.awaitingRetry(new JobRecord.Attempt(attemptNumber, now,
                    JobRecord.Attempt.Outcome.FAILED, error), nextDue);
            queue.save(retrying);
            return retrying;
        }
    }

    private static String errorOf(Exception failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
