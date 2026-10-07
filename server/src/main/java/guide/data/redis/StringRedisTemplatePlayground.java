package guide.data.redis;

import guide.domain.redis.RedisPlayground;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The LIVE playground: the whole port over one StringRedisTemplate. Every
 * method is the same three moves — PREFIX THE KEY, one template call,
 * translate the answer into the port's Optional/boolean/List vocabulary —
 * which is the lesson this adapter exists to teach: Redis's own API is
 * already a port; Spring's template only adds serialization and checked
 * exceptions to it.
 *
 * The NAMESPACE is applied HERE and only here: opsForValue().set("k") would
 * happily write a bare "k" onto the shared server; every call in this file
 * says RedisNamespace.prefixed(key) instead, and every answer that carries
 * a key (namespaced()) reports the full form. Callers above the port never
 * see or build the prefix — if they did, a rename would hunt them all down.
 *
 * TTL seconds round UP from the template's millisecond-resolution answer
 * (getExpire), matching the fake and never overstating remaining life.
 */
public final class StringRedisTemplatePlayground implements RedisPlayground {

    private final StringRedisTemplate template;

    public StringRedisTemplatePlayground(StringRedisTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    @Override
    public String namespaced(String bareKey) {
        return RedisNamespace.prefixed(bareKey);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(template.hasKey(RedisNamespace.prefixed(key)));
    }

    @Override
    public String set(String key, String value) {
        template.opsForValue().set(RedisNamespace.prefixed(key), value);
        return "OK";
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(template.opsForValue().get(RedisNamespace.prefixed(key)));
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(template.delete(RedisNamespace.prefixed(key)));
    }

    @Override
    public void hashSet(String key, String field, String value) {
        template.<String, String>opsForHash().put(RedisNamespace.prefixed(key), field, value);
    }

    @Override
    public Map<String, String> hashGetAll(String key) {
        Map<String, String> hash = template.<String, String>opsForHash()
                .entries(RedisNamespace.prefixed(key));
        // Field-sorted for a deterministic wire order, same discipline as smembers.
        Map<String, String> ordered = new LinkedHashMap<>();
        new TreeSet<>(hash.keySet()).forEach(field -> ordered.put(field, hash.get(field)));
        return ordered;
    }

    @Override
    public long listPush(String key, String value) {
        Long size = template.opsForList().leftPush(RedisNamespace.prefixed(key), value);
        return size != null ? size : 0;
    }

    @Override
    public List<String> listRange(String key, long start, long stop) {
        List<String> slice = template.opsForList().range(RedisNamespace.prefixed(key), start, stop);
        return slice != null ? List.copyOf(slice) : List.of();
    }

    @Override
    public long setAdd(String key, String member) {
        Long added = template.opsForSet().add(RedisNamespace.prefixed(key), member);
        return added != null ? added : 0;
    }

    @Override
    public Set<String> setMembers(String key) {
        Set<String> members = template.opsForSet().members(RedisNamespace.prefixed(key));
        if (members == null) {
            return Set.of();
        }
        // Redis does not define SMEMBERS order; sorted keeps the wire deterministic
        // and identical to the fake — an assertion the tests rely on. LinkedHashSet,
        // not Set.copyOf: copyOf rehashes away the order.
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(members)));
    }

    @Override
    public long zAdd(String key, double score, String member) {
        Boolean added = template.opsForZSet()
                .add(RedisNamespace.prefixed(key), member, score);
        return Boolean.TRUE.equals(added) ? 1 : 0;
    }

    @Override
    public List<ScoredMember> zRange(String key, long start, long stop) {
        var tuples = template.opsForZSet()
                .rangeWithScores(RedisNamespace.prefixed(key), start, stop);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
                .map(tuple -> new ScoredMember(tuple.getValue(), tuple.getScore()))
                .toList();
    }

    @Override
    public void setWithTtl(String key, String value, Duration ttl) {
        // SET key value EX ttl — value and death sentence in one atomic write,
        // never the two-step set-then-expire that loses the race on crash.
        template.opsForValue().set(RedisNamespace.prefixed(key), value, ttl);
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(template.expire(RedisNamespace.prefixed(key), ttl));
    }

    @Override
    public Optional<Long> ttlOf(String key) {
        Long seconds = template.getExpire(RedisNamespace.prefixed(key));
        if (seconds == null || seconds < 0) {
            // -1 no expiry, -2 no key — collapsed; exists() disambiguates.
            return Optional.empty();
        }
        return Optional.of(seconds);
    }

    @Override
    public long publish(String channel, String message) {
        Long receivers = template.convertAndSend(RedisNamespace.prefixed(channel), message);
        return receivers != null ? receivers : 0;
    }
}
