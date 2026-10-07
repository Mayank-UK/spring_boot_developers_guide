package guide.domain.jobs;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Use case: put work on the queue. Validation happens HERE, at the
 * boundary the domain controls — an unknown type, a payload that is not
 * JSON, or an out-of-range injection knob is a validation failure (400)
 * before anything is queued, because a queued record nobody can execute is
 * a dead letter with extra steps.
 *
 * The enqueuer's contract with the caller is DELIBERATE: enqueueing
 * succeeds when the work is DURABLY DESCRIBED, not when it is done — the
 * response is the queued record, and the two observability numbers
 * (depth, oldest age) move the moment this returns.
 */
public final class EnqueueJob {

    /** The demo's attempt budget — three tries, then the dead-letter queue. */
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** Injection ceiling: high enough to demo every state, low enough to not hang a tour. */
    static final int MAX_FAIL_ATTEMPTS = 50;

    public record Request(String type, String payload, Integer failAttempts) {
    }

    private final JobQueue queue;
    private final Clock clock;
    private final ObjectMapper mapper;

    public EnqueueJob(JobQueue queue, Clock clock, ObjectMapper mapper) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Result<JobRecord> execute(Request request) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "type is required (one of: " + JobType.wireNames() + ")"));
        }
        JobType type = JobType.fromWireName(request.type()).orElse(null);
        if (type == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unknown type '" + request.type().trim() + "' (one of: "
                            + JobType.wireNames() + ")"));
        }
        String payload = request.payload() == null || request.payload().isBlank()
                ? "{}"
                : request.payload().trim();
        if (!isJsonObject(payload)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "payload must be a JSON object"));
        }
        int failAttempts = request.failAttempts() == null ? 0 : request.failAttempts();
        if (failAttempts < 0 || failAttempts > MAX_FAIL_ATTEMPTS) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "failAttempts must be between 0 and " + MAX_FAIL_ATTEMPTS));
        }
        JobRecord job = new JobRecord("job_" + UUID.randomUUID(), type, payload,
                JobRecord.JobState.QUEUED, DEFAULT_MAX_ATTEMPTS, failAttempts,
                java.util.List.of(), null, clock.instant(), null, null,
                clock.instant(), 0);
        queue.enqueue(job);
        return Result.success(job);
    }

    private boolean isJsonObject(String payload) {
        try {
            return mapper.readTree(payload).isObject();
        } catch (RuntimeException malformed) {
            return false;
        }
    }
}
