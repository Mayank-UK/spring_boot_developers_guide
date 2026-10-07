package guide.domain.scaling;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The EXTERNALIZED state stand-in for the scaling:stateless demo (topic
 * 40): one counter every persona increments and reads through the same
 * object — the role redis or PostgreSQL plays in a real deployment,
 * reached over the network, owned by no application instance. The demo
 * keeps it in-process so the arithmetic is deterministic and needs no
 * running redis; the OWNERSHIP lesson and the wire shape are identical.
 *
 * What travels to production is the discipline, not the class: the value
 * that matters lives OUTSIDE the replicas (a datastore with its own
 * lifecycle, its own scaling story, its own backups), so any instance can
 * answer any request and an instance dying never takes the value with it.
 * Topic 19/20's compose rails are exactly this arrangement — every server
 * replica pointed at the same mongo/redis service by name.
 */
public final class ExternalCounterStore {

    private final AtomicLong value = new AtomicLong();

    /** One more increment every replica shares — INCR in redis, UPDATE in SQL. */
    public long increment() {
        return value.incrementAndGet();
    }

    /** The shared truth — same answer from any replica, in any order. */
    public long value() {
        return value.get();
    }

    /** Zeroes the shared store (the demo's reset rail). */
    public void reset() {
        value.set(0);
    }
}
