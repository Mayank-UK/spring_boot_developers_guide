package guide.data.redis;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import guide.core.errors.UnavailableException;

/**
 * The 'redis' area's lazy LIVE ACTIVATION — the optional-service discipline,
 * mechanically. Nothing Redis-shaped happens at boot; the first call that
 * arrives while the area is LIVE pays a PING under a monitor (double-checked
 * on a volatile flag), and only a server that answered starts the pub/sub
 * listener container — which is BUILT on demand rather than registered, so
 * Spring's lifecycle processor (which resolves and starts SmartLifecycle
 * beans at refresh even through @Lazy) can never subscribe at boot; this
 * class holds the only start() call, and the destroy() hook stops what it
 * started because no registered lifecycle will.
 *
 * Classification is the whole point: a connection failure — server absent,
 * wrong host, port down — becomes the taxonomy's typed UNAVAILABLE failure
 * (503 service_unavailable, area: redis), the same envelope every degraded
 * dependency serves, and NEVER a boot problem: the flag stays DOWN, so the
 * retry after compose brings the service up pays the activation once more,
 * successfully. Any other Spring Data Redis exception is a genuine data or
 * programming error and propagates untouched — a WRONGTYPE is a bug to
 * fix, not an outage to swallow.
 */
public final class LiveRedisArea implements org.springframework.beans.factory.DisposableBean {

    private final Supplier<StringRedisTemplate> template;
    private final Supplier<RedisMessageListenerContainer> listenerContainer;

    private final Object activationMonitor = new Object();
    private volatile boolean liveActivated;
    private volatile RedisMessageListenerContainer startedContainer;

    public LiveRedisArea(Supplier<StringRedisTemplate> template,
            Supplier<RedisMessageListenerContainer> listenerContainer) {
        this.template = Objects.requireNonNull(template, "template");
        this.listenerContainer = Objects.requireNonNull(listenerContainer, "listenerContainer");
    }

    /**
     * Idempotent activate-once: ping the server, start the subscription.
     * Every live playground call runs this first, so nothing above the
     * adapter layer knows activation exists.
     */
    public void ensureConnected() {
        if (liveActivated) {
            return;
        }
        synchronized (activationMonitor) {
            if (liveActivated) {
                return;
            }
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
                // Spring Data Redis translates even PING oddities into the DAO
                // hierarchy; at activation time, before any command of ours ran,
                // anything in that family means "cannot reach a working server".
                throw unavailable(e);
            }
            RedisMessageListenerContainer container = listenerContainer.get();
            if (!container.isRunning()) {
                container.start();
            }
            startedContainer = container;
            // Subscription handed off BEFORE the flag flips — a caller that
            // passed the first check sees a fully-activated area or none.
            liveActivated = true;
        }
    }

    /**
     * Context shutdown: the subscription container was never a registered
     * bean (the lifecycle processor starts SmartLifecycle beans at refresh
     * even through @Lazy), so nobody else will stop it — this hook does.
     */
    @Override
    public void destroy() {
        RedisMessageListenerContainer container = startedContainer;
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    private static UnavailableException unavailable(Exception cause) {
        return new UnavailableException(
                "Redis is unreachable — the playground area is degraded until the service returns ("
                        + cause.getClass().getSimpleName() + ")",
                java.util.Map.of("area", guide.domain.redis.RedisPlayground.AREA), cause);
    }
}
