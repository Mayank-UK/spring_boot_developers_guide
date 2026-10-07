package guide.data.jobs;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The @Scheduled cadence made observable: a ring of the last ticks. The
 * heartbeat exists so the scheduling machinery is PROVABLE from the
 * outside — {@code GET /api/demo/jobs/heartbeat} answers with the tick
 * timestamps, and a growing ring is the evidence that @EnableScheduling is
 * alive in this process at all (the consolidation this topic performs:
 * one annotation, every ticker beneath it).
 *
 * Bounded by design: a ring of ten plus a monotonic total, one instant
 * per second — observability that costs nothing, which is also the
 * lesson the ring teaches about scheduling hygiene: a tick should be
 * cheap, idempotent, and safe to run every second forever.
 */
public final class JobsHeartbeat {

    static final int RING_SIZE = 10;

    private final Clock clock;
    private final Deque<Instant> ring = new ArrayDeque<>();
    private long totalTicks;

    public JobsHeartbeat(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One beat: remember now, bounded. */
    @Scheduled(fixedDelay = 1000)
    public synchronized void beat() {
        ring.addLast(clock.instant());
        while (ring.size() > RING_SIZE) {
            ring.removeFirst();
        }
        totalTicks += 1;
    }

    public synchronized HeartbeatView view() {
        return new HeartbeatView(new ArrayList<>(ring), totalTicks);
    }

    public record HeartbeatView(List<Instant> ticks, long totalTicks) {
    }
}
