package guide.data.jobs;

import guide.domain.jobs.JobWorker;
import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Adapter: the heartbeat that turns wall-clock time into queue progress —
 * the webhook ticker's exact shape, restated for jobs. One @Scheduled tick
 * per second calls {@code worker.runOnce(now)} — the WORKER decides what
 * is due, so the scheduler carries no policy and the same runOnce serves
 * tests with constructed instants instead of sleeping.
 *
 * fixedDelay (not fixedRate) on purpose: a tick that overruns delays the
 * next instead of stacking concurrent passes on the shared queue. The
 * worker's own TICK_BATCH bounds one pass, so a full queue drains in
 * bounded waves rather than one unbounded burst.
 *
 * Lifecycle honesty: this ticker is ALWAYS ON for the demo — a queue
 * teaches by being live-but-idle (one boolean-ish claim scan per second
 * against an empty store costs nothing), unlike the messaging relay's
 * gated ticker, which must stay inert until real infrastructure is
 * activated. The distinction is the topic's: in-memory polling is free;
 * polling that touches shared state is a consumer you must gate.
 */
public final class JobWorkerTicker {

    private final JobWorker worker;
    private final Clock clock;

    public JobWorkerTicker(JobWorker worker, Clock clock) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One beat: claim and run everything time has made due. */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        worker.runOnce(clock.instant());
    }
}
