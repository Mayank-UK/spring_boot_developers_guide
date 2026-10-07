package guide.domain.caching;

import java.util.Objects;

/**
 * The annotation path's INSTRUMENTATION — and the explicit answer to "how
 * do I know @Cacheable did anything?" You cannot ask the annotation. So this
 * wrapper reads the one observable that cannot lie: {@link TopicStats}
 * #computeCount(). Snapshot before the call, diff after: the compute did
 * not run ⇒ HIT; it ran ⇒ MISS. (Timings are evidence; counts are proof.)
 *
 * The wrapper is also the proxy lesson made structural: it holds the
 * injected {@link AnnotatedTopicStats}, and the container hands it the
 * PROXY, so every call crosses the advice. Had this wrapper's logic lived
 * INSIDE AnnotatedTopicStats as a method calling {@code this.reportJson},
 * it would have cached nothing at all — the trap this topic demonstrates
 * with a test.
 */
public final class AnnotationPathStats {

    private final AnnotatedTopicStats cachePoint;
    private final TopicStats slow;
    private final ReportCodec codec;

    public AnnotationPathStats(AnnotatedTopicStats cachePoint, TopicStats slow, ReportCodec codec) {
        this.cachePoint = Objects.requireNonNull(cachePoint, "cachePoint");
        this.slow = Objects.requireNonNull(slow, "slow");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** One instrumented read through the PROXY — the annotation path, measured. */
    public StatsOutcome stats(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("topicId must be a non-blank string");
        }
        long computesBefore = slow.computeCount();
        long startedAt = System.nanoTime();

        String encoded = cachePoint.reportJson(topicId);

        CacheVerdict verdict = slow.computeCount() == computesBefore
                ? CacheVerdict.HIT
                : CacheVerdict.MISS;
        return new StatsOutcome(codec.decode(encoded), verdict,
                Math.max(0, (System.nanoTime() - startedAt) / 1_000_000),
                AnnotatedTopicStats.CACHE_NAME + ":" + topicId);
    }

    /** Invalidation through the proxy — the @CacheEvict advice fires on this call. */
    public void evict(String topicId) {
        cachePoint.evict(topicId);
    }
}
