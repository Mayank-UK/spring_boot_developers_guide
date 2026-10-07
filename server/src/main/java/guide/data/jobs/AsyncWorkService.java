package guide.data.jobs;

import guide.domain.jobs.AsyncWorkBoard;
import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.annotation.Async;

/**
 * The @Async contrast specimen: slow work that rides an executor and
 * nothing else. One method, one annotation, one named executor — and the
 * console gets to watch what fire-and-forget actually means: the ticket
 * goes pending → running (on a pool thread, named for all to see) → done,
 * with no retry, no attempt history, and no dead letter if the process
 * dies mid-sleep.
 *
 * Two load-bearing details for the topic's caveats:
 * - The executor is NAMED ({@code jobsDemoExecutor}) — @Async without a
 *   qualifier silently binds whatever executor exists, which is how
 *   unrelated work ends up competing on one pool.
 * - The class must be a PROXY-able bean and callers must go THROUGH the
 *   proxy: this service is injected and called from the controller, never
 *   self-invoked — the aop topic's self-invocation trap applies to @Async
 *   exactly as it does to @Transactional (a self-call skips the proxy and
 *   the method runs on the caller's thread, annotation and all).
 */
public class AsyncWorkService {

    private final AsyncWorkBoard board;
    private final Clock clock;

    public AsyncWorkService(AsyncWorkBoard board, Clock clock) {
        this.board = Objects.requireNonNull(board, "board");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The slow work. On saturation this still "succeeds": the executor's
     * CallerRunsPolicy executes the task on the SUBMITTING thread, so the
     * ticket's threadName says so — degradation made visible instead of
     * made fatal (an AbortPolicy would throw back into the request).
     */
    @Async("jobsDemoExecutor")
    public void runSlowWork(String ticketId, long delayMs) {
        board.markRunning(ticketId, Thread.currentThread().getName(), clock.instant());
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // shutdown honesty: stop, stay flagged
        }
        board.markDone(ticketId, clock.instant());
    }
}
