package guide.domain.jobs;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Objects;

/**
 * Use case: one job's full lifecycle trace — the record, its attempt
 * history, the retry budget, the error trail. This is the view an operator
 * stares at in the dead-letter queue before deciding to replay: three
 * identical 421s tells a different story than 421, timeout, refused.
 *
 * The view mapping is shared (static) so the enqueue, replay, and
 * dead-letter endpoints answer in the SAME shape — the console renders one
 * kind of row everywhere.
 */
public final class GetJob {

    public record AttemptView(int n, String at, String outcome, String error) {
    }

    public record JobView(
            String id,
            String type,
            String payload,
            String state,
            int maxAttempts,
            int attemptsLeft,
            int failFirstAttempts,
            List<AttemptView> attempts,
            String lastError,
            String queuedAt,
            String startedAt,
            String finishedAt,
            String nextAttemptAt,
            int replays) {
    }

    private final JobQueue queue;

    public GetJob(JobQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    public Result<JobView> execute(String id) {
        return queue.find(id)
                .map(GetJob::jobView)
                .map(Result::<JobView>success)
                .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                        "no job with id '" + id + "'")));
    }

    /** The one true mapping from record to wire shape — every endpoint reuses it. */
    public static JobView jobView(JobRecord job) {
        List<AttemptView> attempts = job.attempts().stream()
                .map(attempt -> new AttemptView(attempt.n(), attempt.at().toString(),
                        attempt.outcome().wireName(), attempt.error()))
                .toList();
        return new JobView(job.id(), job.type().wireName(), job.payload(),
                job.state().wireName(), job.maxAttempts(), job.attemptsLeft(),
                job.failFirstAttempts(), attempts, job.lastError(),
                job.queuedAt().toString(),
                job.startedAt() == null ? null : job.startedAt().toString(),
                job.finishedAt() == null ? null : job.finishedAt().toString(),
                job.nextAttemptAt() == null ? null : job.nextAttemptAt().toString(),
                job.replays());
    }
}
