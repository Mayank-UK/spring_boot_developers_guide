package guide.data.jobs;

import guide.core.result.Result;
import guide.domain.jobs.AsyncWorkBoard;
import guide.domain.reset.ResettableDemo;
import java.util.Objects;

/**
 * ResettableDemo adapter for the jobs console (topic 29): the queue, the
 * recording sender's receipts, and the @Async board, all in memory, so the
 * reset is three clears and cannot fail — infra-less on purpose, the same
 * per-run-state honesty the queue itself teaches. The console's own
 * endpoints keep reading the same stores right through the reset.
 */
public final class JobsDemoReset implements ResettableDemo {

    /** The shared sweep's stable key for this area. */
    static final String AREA = "jobs";

    private final InMemoryJobQueue jobQueue;
    private final RecordingEmailSender emailSender;
    private final AsyncWorkBoard asyncWorkBoard;

    public JobsDemoReset(InMemoryJobQueue jobQueue, RecordingEmailSender emailSender,
            AsyncWorkBoard asyncWorkBoard) {
        this.jobQueue = Objects.requireNonNull(jobQueue, "jobQueue");
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender");
        this.asyncWorkBoard = Objects.requireNonNull(asyncWorkBoard, "asyncWorkBoard");
    }

    @Override
    public String id() {
        return AREA;
    }

    @Override
    public Result<String> reset() {
        jobQueue.clear();
        emailSender.clear();
        asyncWorkBoard.clear();
        return Result.success("queue, dead letters, sent mail and async tickets cleared");
    }
}
