package guide.data.caching;

import guide.domain.redis.CacheBackend;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;
import tools.jackson.databind.ObjectMapper;

/**
 * A Spring {@link CacheManager} built OVER the CacheBackend port — the piece
 * that lets the annotation path (@Cacheable/@CacheEvict) and the explicit
 * path (CachedTopicStats) share ONE backend, one namespace
 * (guide:cache:… on the Redis side), and one TTL policy.
 *
 * This adapter IS the annotation abstraction's price tag, made visible: the
 * Cache interface speaks Objects, the port speaks Strings, so the manager
 * owns serialization (Jackson for anything not already a String — the
 * demo's annotated method deliberately returns the encoded form, so both
 * caching paths store byte-identical entries), per-cache TTLs (a Cache
 * entry has no expiry in the interface — the TTL the port REQUIRES must
 * come from somewhere, and "per cache name, decided once at wiring" is the
 * honest somewhere), and addressing (the manager prefixes each key with its
 * cache's NAME: cache topicStats, key "rest" ⇒ port key "topicStats:rest"
 * — one namespace per cache, exactly like the explicit path's "stats:"
 * prefix, and for exactly the same reason: keys from different questions
 * must never collide).
 */
public final class CacheBackendCacheManager implements CacheManager {

    private final CacheBackend backend;
    private final Map<String, Duration> ttlsByCacheName;
    private final Duration defaultTtl;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public CacheBackendCacheManager(CacheBackend backend, Map<String, Duration> ttlsByCacheName,
            Duration defaultTtl, ObjectMapper mapper) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.ttlsByCacheName = Map.copyOf(ttlsByCacheName);
        this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name,
                cacheName -> new CacheBackendCache(cacheName,
                        ttlsByCacheName.getOrDefault(cacheName, defaultTtl)));
    }

    /** The declared caches — a whitelist, on purpose: cache names are wiring decisions. */
    @Override
    public Collection<String> getCacheNames() {
        return Set.copyOf(ttlsByCacheName.keySet());
    }

    /** One named cache over the shared port: addressing, TTL, and serialization live here. */
    private final class CacheBackendCache implements Cache {

        private final String name;
        private final Duration ttl;

        CacheBackendCache(String name, Duration ttl) {
            this.name = name;
            this.ttl = ttl;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return backend;
        }

        /** The manager's addressing: cache name + ":" + key — the namespace lesson again. */
        private String address(Object key) {
            return name + ":" + key;
        }

        @Override
        public ValueWrapper get(Object key) {
            return backend.get(address(key))
                    .map(stored -> (ValueWrapper) new SimpleValueWrapper(materialize(stored)))
                    .orElse(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Class<T> type) {
            return backend.get(address(key))
                    .map(stored -> (T) materializeAs(stored, type))
                    .orElse(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueSupplier) {
            ValueWrapper existing = get(key);
            if (existing != null) {
                return (T) existing.get();
            }
            try {
                T computed = valueSupplier.call();
                if (computed != null) {
                    put(key, computed);
                }
                return computed;
            } catch (Exception computationFailed) {
                // the loader path's contract: a failed compute surfaces as the
                // cache abstraction's typed wrapper, never as a raw Exception.
                throw new ValueRetrievalException(key, valueSupplier, computationFailed);
            }
        }

        @Override
        public void put(Object key, Object value) {
            if (value == null) {
                // The port stores Strings, not "nothing": a null put is an evict —
                // the demo's cached methods never return null, and if yours do, you
                // want a null-capable strategy decided OUT LOUD, not by accident.
                evict(key);
                return;
            }
            backend.put(address(key), toStored(value), ttl);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            String address = address(key);
            if (backend.get(address).isPresent()) {
                return get(key);
            }
            put(key, value);
            return null;
        }

        @Override
        public void evict(Object key) {
            backend.evict(address(key));
        }

        @Override
        public void clear() {
            // The port's honest edge: get/put/evict — no enumeration, no scan.
            // "Clear everything" is FLUSH-shaped power this seam refuses on purpose;
            // evict the keys you own, by name.
            throw new UnsupportedOperationException(
                    "the port has no enumeration — evict named keys, not the world");
        }

        /** The stored form: Strings pass through (the demo's choice), everything else is JSON. */
        private String toStored(Object value) {
            return value instanceof String text ? text : mapper.writeValueAsString(value);
        }

        /** The untyped read: a stored String IS the value; other JSON comes back structural. */
        private Object materialize(String stored) {
            return stored;
        }

        /** The typed read the interceptor prefers: String stays, JSON decodes to the class. */
        private Object materializeAs(String stored, Class<?> type) {
            if (type == String.class) {
                return stored;
            }
            return mapper.readValue(stored, type);
        }
    }
}
