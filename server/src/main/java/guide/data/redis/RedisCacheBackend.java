package guide.data.redis;

import guide.domain.redis.CacheBackend;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The CacheBackend port's Redis adapter — the substrate topic 21 (caching)
 * talks through when it builds cache-aside on a real server. It is the
 * namespace lesson applied a second time with a different prefix
 * ({@code guide:cache:}): the port stays clean (bare cache keys), this
 * adapter keeps the guide's cache entries separable from everything else
 * on the shared server, and a topic-21 rename touches exactly one constant.
 *
 * Registered @Lazy in RedisDemoConfig: nothing here is constructed until
 * topic 21 asks for a CacheBackend bean — this file is the seam, delivered
 * with its adapter so the port never dangles unimplemented.
 */
public final class RedisCacheBackend implements CacheBackend {

    /** The cache namespace — the demo playground's guide:demo: sibling, deliberately distinct. */
    public static final String CACHE_PREFIX = "guide:cache:";

    private final StringRedisTemplate template;

    public RedisCacheBackend(StringRedisTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(template.opsForValue().get(CACHE_PREFIX + key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("cache entries must carry a positive ttl");
        }
        template.opsForValue().set(CACHE_PREFIX + key, value, ttl);
    }

    @Override
    public void evict(String key) {
        template.delete(CACHE_PREFIX + key);
    }
}
