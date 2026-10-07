package guide.domain.caching;

/**
 * The expensive-read PORT: compute one topic's word-count analytics from the
 * posts use cases — the work a cache exists to skip.
 *
 * {@link #computeCount()} is the INSTRUMENTATION SEAM, and it is part of the
 * contract on purpose: everything this topic proves about caching — a hit, a
 * miss, a stampede, the self-invocation trap — is proven by whether the
 * COMPUTE ran, not by timings. The count is the observable truth both the
 * explicit cache-aside wrapper and the @Cacheable instrumentation read to
 * report HIT or MISS.
 */
public interface TopicStats {

    /** Word-count analytics for every post carrying the tag; never null. */
    TopicStatsReport compute(String topicId);

    /** How many times compute has actually run — the caching proofs' ground truth. */
    long computeCount();
}
