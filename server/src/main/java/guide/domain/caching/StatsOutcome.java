package guide.domain.caching;

import java.util.Objects;

/**
 * One instrumented read: the answer, whether it came from the cache, how
 * long the CALL took (a hit is fast because the compute never ran — the
 * number is evidence, not the proof; the compute COUNT is the proof), and
 * the key the outcome traveled under.
 */
public record StatsOutcome(TopicStatsReport result, CacheVerdict cache, long computeTimeMs, String key) {

    public StatsOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cache, "cache");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must be a non-blank string");
        }
        if (computeTimeMs < 0) {
            computeTimeMs = 0;
        }
    }
}
