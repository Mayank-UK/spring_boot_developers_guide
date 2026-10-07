package guide.domain.caching;

import guide.domain.redis.CacheBackend;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The EXPLICIT cache-aside wrapper — the pattern spelled out in plain Java,
 * no annotations anywhere: GET the key; a hit answers without touching the
 * expensive read; a miss computes, PUTS the answer WITH a TTL, and answers.
 *
 * Every decision here is one the annotation path makes FOR you (and hides):
 * the key's shape ("stats:" + topicId — one namespace per cached question,
 * so the explicit and annotated paths can never collide on the same
 * backend), the TTL (60 s — a safety net against unbounded staleness, not a
 * freshness strategy), what gets cached (the answer INCLUDING the empty one
 * — negative caching), and when the cache is consulted (every read, with
 * invalidation an explicit, deliberate act through {@link #evict}).
 */
public final class CachedTopicStats {

    /** The safety-net TTL: how long a stale answer may serve before recompute is forced. */
    public static final Duration TTL = Duration.ofSeconds(60);

    /** The key an explicit-path answer lives under — "stats:" is the path's namespace. */
    public static String keyFor(String topicId) {
        return "stats:" + topicId;
    }

    private final CacheBackend cache;
    private final TopicStats slow;
    private final ReportCodec codec;

    public CachedTopicStats(CacheBackend cache, TopicStats slow, ReportCodec codec) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.slow = Objects.requireNonNull(slow, "slow");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** One read-through: hit → decode and answer; miss → compute, put with TTL, answer. */
    public StatsOutcome stats(String topicId) {
        requireTopicId(topicId);
        String key = keyFor(topicId);
        long startedAt = System.nanoTime();

        Optional<String> hit = cache.get(key);
        if (hit.isPresent()) {
            return new StatsOutcome(codec.decode(hit.get()), CacheVerdict.HIT,
                    elapsedMs(startedAt), key);
        }
        TopicStatsReport report = slow.compute(topicId);
        cache.put(key, codec.encode(report), TTL);
        return new StatsOutcome(report, CacheVerdict.MISS, elapsedMs(startedAt), key);
    }

    /** The explicit invalidation act: drop the key; the next read recomputes. */
    public void evict(String topicId) {
        requireTopicId(topicId);
        cache.evict(keyFor(topicId));
    }

    private static void requireTopicId(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("topicId must be a non-blank string");
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
