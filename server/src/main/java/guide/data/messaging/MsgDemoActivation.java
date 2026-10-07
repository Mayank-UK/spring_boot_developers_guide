package guide.data.messaging;

import guide.core.errors.UnavailableException;
import guide.data.sqldemo.DbDemoActivation;
import guide.domain.messaging.TeachingEventPublisher;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The messaging area's once-guarded LIVE ACTIVATION — the optional-service
 * discipline, third data substrate, same shape: nothing PostgreSQL- or
 * Redis-shaped happens at boot; the first demo call pays the bill once,
 * under a monitor; a failed activation stays DOWN so a healed stack retries
 * into success; and every failure on the way is the taxonomy's typed
 * UNAVAILABLE (503, area: msg), never a boot problem.
 *
 * The bill, in order: (1) the DATABASE side — topic 17's DbDemoActivation
 * runs migrations (V20 included, idempotently) and replays the posts seed;
 * (2) the REDIS side — a PING, and only a server that answered moves on;
 * (3) the GROUP — XGROUP CREATE ... MKSTREAM, BUSYGROUP-meaning-exists, so
 * the consumer group is present before any read. Afterwards the flag is up
 * and the relay's ticker (which consults {@link #isActivated()}) may start
 * draining the outbox — THE no-eager-consumers rule: the poller exists as a
 * bean from boot but does NOTHING until this method has succeeded against
 * live infrastructure.
 */
public final class MsgDemoActivation {

    private final Supplier<DbDemoActivation> dbActivation;
    private final Supplier<StringRedisTemplate> template;
    private final Runnable ensureGroup;

    private final Object activationMonitor = new Object();
    private volatile boolean liveActivated;

    public MsgDemoActivation(Supplier<DbDemoActivation> dbActivation,
            Supplier<StringRedisTemplate> template, Runnable ensureGroup) {
        this.dbActivation = Objects.requireNonNull(dbActivation, "dbActivation");
        this.template = Objects.requireNonNull(template, "template");
        this.ensureGroup = Objects.requireNonNull(ensureGroup, "ensureGroup");
    }

    /** Idempotent readiness: pay the bill once; a failed payment retries. */
    public void ensureReady() {
        if (liveActivated) {
            return;
        }
        synchronized (activationMonitor) {
            if (liveActivated) {
                return;
            }
            // 1) PostgreSQL: migrations (V20 outbox included) + the posts seed.
            dbActivation.get().ensureReady();
            // 2) Redis: PING — only a server that answered proceeds.
            try {
                RedisConnection connection = template.get().getConnectionFactory().getConnection();
                try {
                    connection.ping();
                } finally {
                    connection.close();
                }
            } catch (RedisConnectionFailureException e) {
                throw unavailable(e);
            } catch (DataAccessException e) {
                throw unavailable(e);
            }
            // 3) The consumer group, before any read can name it.
            ensureGroup.run();
            // The relay's gate — handed off BEFORE the flag flips, so a
            // caller that passed the first check sees a fully-live area.
            liveActivated = true;
        }
    }

    /** The ticker's gate: false until activation succeeded. */
    public boolean isActivated() {
        return liveActivated;
    }

    private static UnavailableException unavailable(Exception cause) {
        return new UnavailableException(
                "Redis Streams are unreachable — the messaging demo is degraded until the "
                        + "service returns (" + cause.getClass().getSimpleName() + ")",
                Map.of("area", TeachingEventPublisher.AREA), cause);
    }
}
