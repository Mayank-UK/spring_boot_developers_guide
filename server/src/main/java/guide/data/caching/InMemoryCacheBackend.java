package guide.data.caching;

import guide.domain.redis.CacheBackend;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The CacheBackend port's in-memory adapter — the fake side of the caching
 * area's scenario router, and the whole reason the app serves cached reads
 * with Redis absent (the optional-service discipline, fourth application).
 *
 * The fake mirrors the REAL server's semantics honestly: LAZY expiry (an
 * expired entry is simply absent on its next access — nothing reaps at the
 * moment of expiry, exactly like Redis), positive-TTL puts only (an entry
 * without a TTL is an immortal leak wearing a cache's clothes), and DEL
 * semantics on evict. The CLOCK is injectable so tests prove TTL behavior
 * by arithmetic — advance the clock past the deadline, read, observe the
 * miss — never by sleeping.
 */
public final class InMemoryCacheBackend implements CacheBackend {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Supplier<Long> clock;

    /** The production shape: the wall clock. */
    public InMemoryCacheBackend() {
        this(System::currentTimeMillis);
    }

    /** The test shape: a controllable clock makes expiry deterministic. */
    public InMemoryCacheBackend(Supplier<Long> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(live(key)).map(Entry::value);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("cache entries must carry a positive ttl");
        }
        store.put(key, new Entry(value, clock.get() + ttl.toMillis()));
    }

    @Override
    public void evict(String key) {
        store.remove(key);
    }

    /** The demo/tests reset handle — empties the store (the fake IS the store). */
    public void clear() {
        store.clear();
    }

    /** Live view of one entry: absent entries and expired entries are the same miss. */
    private Entry live(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(clock.get())) {
            store.remove(key, entry);
            return null;
        }
        return entry;
    }

    /** A value plus its deadline — the TTL made data. */
    private record Entry(String value, long expiresAtMillis) {

        boolean expired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }
}
