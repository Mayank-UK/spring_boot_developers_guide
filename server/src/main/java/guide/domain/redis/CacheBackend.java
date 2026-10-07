package guide.domain.redis;

import java.time.Duration;
import java.util.Optional;

/**
 * The KV-cache PORT: the narrowest honest surface a cache needs — read one
 * key, write one key WITH its TTL, drop one key. Deliberately kept CLEAN:
 * no namespace, no structures, no pub/sub, no statistics, nothing topic 21
 * (caching) does not strictly need. This is the seam the caching topic's
 * cache-aside flow will talk through; Redis is one possible adapter
 * (guide.data.redis.RedisCacheBackend) and an in-memory TTL map would be
 * another, because the PORT is the contract, not the server.
 *
 * String values on purpose: a cache stores the serialized form (JSON, here)
 * and stays ignorant of what it cached — parsing belongs to the caller who
 * knows the type they put in.
 */
public interface CacheBackend {

    /** The cached value, or empty on a miss (absent, evicted, or expired — a miss is a miss). */
    Optional<String> get(String key);

    /** Caches {@code value} under {@code key} with an expiry — caches without TTLs are leaks with extra steps. */
    void put(String key, String value, Duration ttl);

    /** EVICT — drops the key if present; safe to call unconditionally (DEL's semantics). */
    void evict(String key);
}
