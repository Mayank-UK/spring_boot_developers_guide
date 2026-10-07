package guide.domain.caching;

import java.util.Objects;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

/**
 * The ANNOTATION PATH: the same cache-aside flow, declared instead of
 * written. {@code @Cacheable} on a method means "before this body runs,
 * check the cache named topicStats under the key in the expression; if an
 * entry is there, return it and SKIP the method entirely; if not, run the
 * method and store what it returns." {@code @CacheEvict} means "after this
 * method, drop the entry." The method body is ONLY the compute — the
 * cache-aside choreography moved into PROXY machinery you cannot see from
 * the call site.
 *
 * AND THAT IS THE WARNING, armed on purpose: the annotations live in the
 * PROXY, not in your class. A call through the injected bean crosses the
 * proxy and gets the advice; a call on {@code this} — see
 * {@link #reportJsonBypassingProxy(String)} — is plain Java dispatch and
 * the cache never hears about it. Topic 06 armed this trap for tracing;
 * here it is test-proven against @Cacheable, the aspect you are most likely
 * to trust blindly.
 *
 * Deliberately NOT final, with public methods: Spring proxies classes with
 * CGLIB subclasses, and a final class or method cannot be subclassed — the
 * annotation would silently do nothing (another way this path lies).
 */
public class AnnotatedTopicStats {

    /** The cache's NAME — the annotation machinery's address space, "topicStats:<key>". */
    public static final String CACHE_NAME = "topicStats";

    private final TopicStats slow;
    private final ReportCodec codec;

    public AnnotatedTopicStats(TopicStats slow, ReportCodec codec) {
        this.slow = Objects.requireNonNull(slow, "slow");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * The cached method. Returns the report's ENCODED form on purpose: the
     * CacheBackend port speaks String, and a CacheManager built over it must
     * decide serialization for every caller — caching the serialized form
     * itself keeps both caching paths byte-identical in the backend.
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "#topicId")
    public String reportJson(String topicId) {
        return codec.encode(slow.compute(topicId));
    }

    /**
     * THE SELF-INVOCATION TRAP, shipped as a specimen: this method calls
     * {@code this.reportJson(...)} — plain Java dispatch on the target
     * object, INSIDE the proxy's skin. The call never crosses the proxy
     * boundary, the interceptor never runs, the cache is never consulted
     * and never filled. Two calls here compute twice; two calls through the
     * proxy compute once. The proxy-semantics test pins exactly that.
     */
    public String reportJsonBypassingProxy(String topicId) {
        return this.reportJson(topicId);
    }

    /** The annotation path's invalidation: evict AFTER the (empty) body runs. */
    @CacheEvict(cacheNames = CACHE_NAME, key = "#topicId")
    public void evict(String topicId) {
        // empty on purpose — the annotation IS the body's entire meaning here
    }
}
