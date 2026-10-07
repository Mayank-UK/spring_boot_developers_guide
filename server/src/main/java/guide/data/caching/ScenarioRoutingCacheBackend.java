package guide.data.caching;

import guide.core.errors.UnavailableException;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.redis.CacheBackend;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/**
 * The caching area's scenario router — the same shape the posts and redis
 * areas use: read the ScenarioStore's 'caching' area at CALL TIME, serve
 * from the in-memory fake or the Redis adapter (topic 20's @Lazy
 * CacheBackend bean, the port this topic was handed), and let the public
 * PATCH /api/demo/scenario flip the backend at runtime.
 *
 * The 'caching' area carries NO PIN: it follows the global toggle, because
 * a shared cache's liveness IS the story this demo teaches. And the
 * optional-service discipline holds at the router: a live call against an
 * absent Redis is classified into the typed 503 service_unavailable
 * envelope naming the area — never a stack trace, never a failed boot, and
 * the very next call after the service returns just works (Lettuce
 * reconnects per command; nothing here caches its own failure).
 */
public final class ScenarioRoutingCacheBackend implements CacheBackend {

    /** The ScenarioStore area this router answers to. */
    public static final String AREA = "caching";

    private final ScenarioStore scenarioStore;
    private final CacheBackend fake;
    private final Supplier<CacheBackend> live;

    public ScenarioRoutingCacheBackend(ScenarioStore scenarioStore, CacheBackend fake,
            Supplier<CacheBackend> live) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.fake = Objects.requireNonNull(fake, "fake");
        this.live = Objects.requireNonNull(live, "live");
    }

    @Override
    public Optional<String> get(String key) {
        return guarded(() -> delegate().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        guarded(() -> {
            delegate().put(key, value, ttl);
            return null;
        });
    }

    @Override
    public void evict(String key) {
        guarded(() -> {
            delegate().evict(key);
            return null;
        });
    }

    /** Fake unless the 'caching' area reads live — decided per call, so the flip is instant. */
    private CacheBackend delegate() {
        return scenarioStore.dataModeFor(AREA) == ScenarioDataMode.LIVE ? live.get() : fake;
    }

    /** The degrade-gracefully rule, applied at the port's edge: DAO family ⇒ typed unavailable. */
    private <T> T guarded(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException unreachable) {
            throw new UnavailableException(
                    "Redis is unreachable — the caching area is degraded until the service returns",
                    Map.of("area", AREA), unreachable);
        }
    }
}
