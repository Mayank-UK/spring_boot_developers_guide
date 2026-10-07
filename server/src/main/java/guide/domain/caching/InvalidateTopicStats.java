package guide.domain.caching;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: the deliberate invalidation act, for BOTH caching paths at once
 * — because invalidation is hard precisely when more than one cache shape
 * holds the same answer (here: two paths over one backend; in production:
 * two pods over one shared store, or a local map beside a CDN). The demo
 * response lists every key it dropped: an eviction you cannot name did not
 * happen.
 */
public class InvalidateTopicStats {

    private final CachedTopicStats explicit;
    private final AnnotationPathStats annotation;

    public InvalidateTopicStats(CachedTopicStats explicit, AnnotationPathStats annotation) {
        this.explicit = Objects.requireNonNull(explicit, "explicit");
        this.annotation = Objects.requireNonNull(annotation, "annotation");
    }

    /** Drops both paths' entries for the topic; always safe to repeat (DEL semantics). */
    public Result<Evicted> execute(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "topicId must be a non-empty string", Map.of("parameter", "topicId")));
        }
        explicit.evict(topicId);
        annotation.evict(topicId);
        return Result.success(new Evicted(topicId, List.of(
                CachedTopicStats.keyFor(topicId),
                AnnotatedTopicStats.CACHE_NAME + ":" + topicId)));
    }

    /** What the endpoint answers: the topic and every key that left the cache. */
    public record Evicted(String topicId, List<String> keys) {
    }
}
