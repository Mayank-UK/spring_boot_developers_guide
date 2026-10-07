package guide.domain.scaling;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One SIMULATED replica for the scaling:stateless demo (topic 40): a named
 * server persona carrying its own in-memory counter — the private heap a
 * real instance owns and no other instance can see. Two personas live
 * inside this one JVM (the report's processInstanceId is the honesty note:
 * the simulation shares a process, production would not), and both point
 * at the SAME {@link ExternalCounterStore} — the arrangement a load
 * balancer, two pods, and one redis describe.
 *
 * The local counter is deliberately server-local FOREVER: nothing syncs
 * it, nothing rebuilds it, a reset zeroes it. That is the teaching point —
 * memory that lives inside an instance diverges from every other instance
 * by construction, which is harmless for ephemeral telemetry (request
 * counts, in-flight gauges) and fatal for anything a caller depends on.
 */
public final class InstancePersona {

    private final String id;
    private final AtomicLong localState = new AtomicLong();

    public InstancePersona(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("persona id must be non-empty");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** One more request this persona served — the private counter every replica keeps. */
    long incrementLocal() {
        return localState.incrementAndGet();
    }

    /** What this persona's own memory holds (ephemeral, diverging, fine). */
    public long localState() {
        return localState.get();
    }

    /** Zeroes the private memory — a fresh replica, as a pod replacement would be. */
    public void reset() {
        localState.set(0);
    }
}
