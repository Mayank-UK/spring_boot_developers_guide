package guide.data.redis;

import guide.domain.redis.RedisPlayground;
import guide.domain.redis.RecentMessages;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory FAKE playground: the whole teaching surface over
 * ConcurrentHashMaps, so the app boots and the demo works with Redis absent
 * (the optional-service discipline) and the unit tests run without a
 * container. It mirrors the real server's observable CONTRACT, including
 * the two behaviors a fake usually gets wrong: NEGATIVE RANGE INDICES
 * (LRANGE key 0 -1 is the whole list) and LAZY EXPIRY — an expired key is
 * simply absent on the next access, and nothing sweeps it in between,
 * which is (to a first approximation, minus the server's occasional active
 * cycle) exactly how Redis reaps keys. TTL seconds round UP, the direction
 * that never overstates remaining life.
 *
 * Publish short-circuits the broker: fake mode appends straight into the
 * recent ring and reports one receiver (the ring itself). Live mode's
 * round-trip through the real server is the demo's other half.
 */
public final class InMemoryRedisPlayground implements RedisPlayground {

    /** One stored key: its typed payload plus its expiry deadline (0 = none). */
    private static final class Entry {
        private final Object payload;
        private volatile long expiresAtMillis;

        private Entry(Object payload) {
            this.payload = payload;
        }

        private boolean expired(long now) {
            return expiresAtMillis > 0 && expiresAtMillis <= now;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final RecentMessages recent;

    public InMemoryRedisPlayground(RecentMessages recent) {
        this.recent = Objects.requireNonNull(recent, "recent");
    }

    // ---- the namespace, applied at the one door every op walks through ----

    @Override
    public String namespaced(String bareKey) {
        return RedisNamespace.prefixed(bareKey);
    }

    // ---- strings ----

    @Override
    public boolean exists(String key) {
        return live(key) != null;
    }

    @Override
    public String set(String key, String value) {
        Objects.requireNonNull(value, "value");
        store.put(RedisNamespace.prefixed(key), new Entry(value));
        return "OK";
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = live(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!(entry.payload instanceof String value)) {
            throw wrongType(key, entry, "string");
        }
        return Optional.of(value);
    }

    @Override
    public boolean delete(String key) {
        return store.remove(RedisNamespace.prefixed(key)) != null;
    }

    // ---- hashes ----

    @Override
    public void hashSet(String key, String field, String value) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        store.compute(RedisNamespace.prefixed(key), (k, entry) -> {
            if (entry == null || entry.expired(System.currentTimeMillis())) {
                return new Entry(new LinkedHashMap<String, String>(Map.of(field, value)));
            }
            if (!(entry.payload instanceof LinkedHashMap<?, ?> raw)) {
                throw new IllegalStateException(
                        "WRONGTYPE: key " + k + " holds a " + kind(entry.payload) + ", not a hash");
            }
            @SuppressWarnings("unchecked")
            Map<String, String> hash = (Map<String, String>) raw;
            hash.put(field, value);
            return entry;
        });
    }

    @Override
    public Map<String, String> hashGetAll(String key) {
        Entry entry = live(key);
        if (entry == null) {
            return Map.of();
        }
        if (!(entry.payload instanceof LinkedHashMap<?, ?> raw)) {
            throw wrongType(key, entry, "hash");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> hash = (Map<String, String>) raw;
        return Map.copyOf(hash);
    }

    // ---- lists ----

    @Override
    public long listPush(String key, String value) {
        Objects.requireNonNull(value, "value");
        String full = RedisNamespace.prefixed(key);
        synchronized (store) {
            Entry entry = live(key);
            if (entry == null) {
                List<String> list = new ArrayList<>();
                list.add(value);
                store.put(full, new Entry(list));
                return 1;
            }
            requireList(key, entry);
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) entry.payload;
            list.add(0, value);
            return list.size();
        }
    }

    @Override
    public List<String> listRange(String key, long start, long stop) {
        Entry entry = live(key);
        if (entry == null) {
            return List.of();
        }
        requireList(key, entry);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) entry.payload;
        return slice(list, start, stop);
    }

    // ---- sets ----

    @Override
    public long setAdd(String key, String member) {
        Objects.requireNonNull(member, "member");
        synchronized (store) {
            Entry entry = live(key);
            if (entry == null) {
                Set<String> set = new LinkedHashSet<>();
                set.add(member);
                store.put(RedisNamespace.prefixed(key), new Entry(set));
                return 1;
            }
            requireSet(key, entry);
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) entry.payload;
            return set.add(member) ? 1 : 0;
        }
    }

    @Override
    public Set<String> setMembers(String key) {
        Entry entry = live(key);
        if (entry == null) {
            return Set.of();
        }
        requireSet(key, entry);
        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) entry.payload;
        // Sorted, and kept in a LinkedHashSet: Set.copyOf would rehash away the
        // deterministic iteration order the wire contract promises.
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(new java.util.TreeSet<>(set)));
    }

    // ---- sorted sets ----

    @Override
    public long zAdd(String key, double score, String member) {
        Objects.requireNonNull(member, "member");
        synchronized (store) {
            Entry entry = live(key);
            if (entry == null) {
                store.put(RedisNamespace.prefixed(key),
                        new Entry(new TreeMap<>(Map.of(member, score))));
                return 1;
            }
            requireSortedSet(key, entry);
            @SuppressWarnings("unchecked")
            TreeMap<String, Double> scores = (TreeMap<String, Double>) entry.payload;
            return scores.put(member, score) == null ? 1 : 0;
        }
    }

    @Override
    public List<ScoredMember> zRange(String key, long start, long stop) {
        Entry entry = live(key);
        if (entry == null) {
            return List.of();
        }
        requireSortedSet(key, entry);
        @SuppressWarnings("unchecked")
        TreeMap<String, Double> scores = (TreeMap<String, Double>) entry.payload;
        // ZRANGE walks (score, member) order — the sort comes BEFORE the slice.
        List<ScoredMember> byScore = scores.entrySet().stream()
                .map(e -> new ScoredMember(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(ScoredMember::score)
                        .thenComparing(ScoredMember::member))
                .toList();
        return slice(byScore, start, stop);
    }

    // ---- expiry (the lazy model, mirrored) ----

    @Override
    public void setWithTtl(String key, String value, Duration ttl) {
        requirePositive(ttl);
        Objects.requireNonNull(value, "value");
        Entry entry = new Entry(value);
        entry.expiresAtMillis = System.currentTimeMillis() + ttl.toMillis();
        store.put(RedisNamespace.prefixed(key), entry);
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        requirePositive(ttl);
        Entry entry = live(key);
        if (entry == null) {
            return false;
        }
        entry.expiresAtMillis = System.currentTimeMillis() + ttl.toMillis();
        return true;
    }

    @Override
    public Optional<Long> ttlOf(String key) {
        Entry entry = live(key);
        if (entry == null || entry.expiresAtMillis == 0) {
            return Optional.empty();
        }
        long remaining = entry.expiresAtMillis - System.currentTimeMillis();
        return Optional.of(Math.max(1, (remaining + 999) / 1000));
    }

    // ---- pub/sub (short-circuited: straight into the ring) ----

    @Override
    public long publish(String channel, String message) {
        recent.append(RedisNamespace.prefixed(channel), message);
        return 1;
    }

    // ---- test/demo handles ----

    /** The prefixed keys currently live (post-expiry) — how tests assert the namespace. */
    public Set<String> keys() {
        return store.entrySet().stream()
                .filter(e -> !e.getValue().expired(System.currentTimeMillis()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    /** Deterministic reset (tests) — the empty, unexpired keyspace. */
    public void clear() {
        store.clear();
    }

    // ---- internals ----

    /** The entry at bare {@code key} if it exists AND has not expired — lazy expiry applied on every read. */
    private Entry live(String key) {
        String full = RedisNamespace.prefixed(key);
        Entry entry = store.get(full);
        if (entry == null) {
            return null;
        }
        if (entry.expired(System.currentTimeMillis())) {
            store.remove(full, entry);
            return null;
        }
        return entry;
    }

    /** Redis's inclusive [start, stop] slice with negative indices counting from the end (-1 = last). */
    private static <T> List<T> slice(List<T> list, long start, long stop) {
        int size = list.size();
        int from = normalize(start, size);
        int to = normalize(stop, size);
        if (from > to || from >= size) {
            return List.of();
        }
        return List.copyOf(list.subList(from, Math.min(to + 1, size)));
    }

    private static int normalize(long index, int size) {
        long resolved = index < 0 ? size + index : index;
        return (int) Math.max(0, Math.min(resolved, size));
    }

    private static void requirePositive(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private static void requireList(String key, Entry entry) {
        if (!(entry.payload instanceof List<?>)) {
            throw wrongType(key, entry, "list");
        }
    }

    private static void requireSet(String key, Entry entry) {
        if (!(entry.payload instanceof Set<?>)) {
            throw wrongType(key, entry, "set");
        }
    }

    private static void requireSortedSet(String key, Entry entry) {
        if (!(entry.payload instanceof TreeMap<?, ?>)) {
            throw wrongType(key, entry, "sorted set");
        }
    }

    private static IllegalStateException wrongType(String key, Entry entry, String wanted) {
        return new IllegalStateException(
                "WRONGTYPE: key " + RedisNamespace.prefixed(key) + " holds a "
                        + kind(entry.payload) + ", not a " + wanted);
    }

    private static String kind(Object payload) {
        if (payload instanceof String) {
            return "string";
        }
        if (payload instanceof Map) {
            return "hash";
        }
        if (payload instanceof List) {
            return "list";
        }
        if (payload instanceof TreeMap) {
            return "sorted set";
        }
        return "set";
    }
}
