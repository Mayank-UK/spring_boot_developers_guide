package guide.domain.jobs;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case: the dead-letter queue's exit door. Replaying enqueues a FRESH
 * record carrying the dead one's type and payload (attempts start over,
 * new id, replays counter carries the lineage) — the dead record STAYS in
 * the DLQ as evidence, because "replayed" is a fact about the response,
 * not a rewrite of the history that justified it.
 *
 * The request's failAttempts knob models the operator's FIX: you replay
 * after the bug or the dependency is repaired, and in this console the
 * injected failure IS the bug — so replaying with failAttempts=0 (the
 * default) models the fixed handler, while replaying with the same
 * injection re-poisons the record and it walks back to the DLQ, which is
 * exactly what replaying without a fix deserves.
 */
public final class ReplayDeadLetter {

    public record Request(Integer failAttempts) {
    }

    private final JobQueue queue;
    private final Clock clock;

    public ReplayDeadLetter(JobQueue queue, Clock clock) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result<JobRecord> execute(String id, Request request) {
        JobRecord dead = queue.find(id).orElse(null);
        if (dead == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "no job with id '" + id + "'"));
        }
        if (dead.state() != JobRecord.JobState.DEAD) {
            return Result.failed(Failure.of(FailureKind.CONFLICT,
                    "only dead-lettered jobs can be replayed — '" + id + "' is "
                            + dead.state().wireName()));
        }
        int failAttempts = request == null || request.failAttempts() == null
                ? 0
                : request.failAttempts();
        if (failAttempts < 0 || failAttempts > EnqueueJob.MAX_FAIL_ATTEMPTS) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "failAttempts must be between 0 and " + EnqueueJob.MAX_FAIL_ATTEMPTS));
        }
        JobRecord replay = dead.replayedAs("job_" + UUID.randomUUID(), failAttempts,
                clock.instant());
        queue.enqueue(replay);
        return Result.success(replay);
    }
}
