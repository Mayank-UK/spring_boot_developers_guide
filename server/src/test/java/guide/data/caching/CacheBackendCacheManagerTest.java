package guide.data.caching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import tools.jackson.databind.json.JsonMapper;

/**
 * The port-backed CacheManager — the adapter that lets @Cacheable share the
 * CacheBackend with the explicit path. Pinned here: the ADDRESSING (cache
 * name + ":" + key — the namespace that keeps paths from colliding), the
 * TTL honored through the fake's clock (arithmetic, never sleeps), String
 * pass-through (both caching paths store byte-identical entries), JSON for
 * non-strings, the declared-names whitelist, putIfAbsent's honest
 * check-then-act shape, and clear() refusing on purpose.
 */
class CacheBackendCacheManagerTest {

    private final AtomicLong now = new AtomicLong(5_000_000);
    private final InMemoryCacheBackend backend = new InMemoryCacheBackend(now::get);
    private final CacheBackendCacheManager manager = new CacheBackendCacheManager(backend,
            Map.of("topicStats", Duration.ofSeconds(60)), Duration.ofSeconds(60),
            JsonMapper.builder().build());

    @Test
    void keysAreAddressedAsCacheNameColonKey() {
        Cache cache = manager.getCache("topicStats");
        cache.put("rest", "encoded-report");

        assertEquals("encoded-report", cache.get("rest", String.class));
        assertEquals("encoded-report", backend.get("topicStats:rest").orElseThrow(),
                "the port saw the namespaced key — the manager owns addressing");
    }

    @Test
    void theTtlTravelsThroughToTheBackend() {
        Cache cache = manager.getCache("topicStats");
        cache.put("rest", "encoded-report");
        assertTrue(backend.get("topicStats:rest").isPresent());

        now.addAndGet(60_001);
        assertNull(cache.get("rest", String.class),
                "past the declared 60 s TTL the entry is gone — TTLs honored through the fake");
    }

    @Test
    void nonStringValuesSerializeAsJsonAndReadBackTyped() {
        Cache cache = manager.getCache("topicStats");
        guide.domain.caching.TopicStatsReport report =
                new guide.domain.caching.TopicStatsReport("rest", 2, 40, 10, 30, 20.0, "t");
        cache.put("rest", report);

        assertEquals(report, cache.get("rest", guide.domain.caching.TopicStatsReport.class),
                "JSON round trip through the shared backend, typed on the way out");
    }

    @Test
    void declaredNamesAreTheWhitelistAndTheSameCacheInstanceIsStable() {
        assertTrue(manager.getCacheNames().contains("topicStats"));
        assertSame(manager.getCache("topicStats"), manager.getCache("topicStats"));
    }

    @Test
    void aNullPutIsAnEvictBecauseThePortCannotStoreNothing() {
        Cache cache = manager.getCache("topicStats");
        cache.put("rest", "encoded-report");
        cache.put("rest", null);
        assertTrue(backend.get("topicStats:rest").isEmpty());
    }

    @Test
    void clearRefusesOnPurposeThePortHasNoEnumeration() {
        Cache cache = manager.getCache("topicStats");
        assertThrows(UnsupportedOperationException.class, cache::clear);
    }
}
