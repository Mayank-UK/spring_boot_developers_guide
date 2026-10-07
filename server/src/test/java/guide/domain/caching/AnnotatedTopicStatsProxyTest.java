package guide.domain.caching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.data.caching.JacksonReportCodec;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * THE ANNOTATION IS AN AOP PROXY — proven, not narrated. A minimal context
 * (no Spring Boot, no Redis: ConcurrentMapCacheManager, the abstraction's
 * built-in in-process manager — which also proves the manager is swappable)
 * loads the annotated service; the container hands out the CGLIB PROXY, and:
 *
 * - two calls through the proxy compute ONCE (the cache worked);
 * - two calls through the SELF-CALL helper compute TWICE — the trap from
 *   topic 06, re-armed against @Cacheable: this.reportJson(...) never
 *   crosses the proxy, so the interceptor never runs;
 * - the bypassed calls never FILL the cache either (the next proxied call
 *   to that key still misses);
 * - @CacheEvict through the proxy evicts (the next call recomputes).
 *
 * All verdicts are COMPUTE COUNTS — the same observable the lab's
 * instrumentation reads.
 */
class AnnotatedTopicStatsProxyTest {

    private static final CountingStats SLOW = new CountingStats();
    private static AnnotationConfigApplicationContext context;

    @BeforeAll
    static void bootMinimalProxyContext() {
        context = new AnnotationConfigApplicationContext(ProxyConfig.class);
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    /** @EnableCaching + the abstraction's built-in manager — the smallest honest stage. */
    @Configuration
    @EnableCaching
    static class ProxyConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(AnnotatedTopicStats.CACHE_NAME);
        }

        @Bean
        TopicStats slowStats() {
            return SLOW;
        }

        @Bean
        ReportCodec reportCodec() {
            return new JacksonReportCodec(JsonMapper.builder().build());
        }

        @Bean
        AnnotatedTopicStats annotatedTopicStats(TopicStats slow, ReportCodec codec) {
            return new AnnotatedTopicStats(slow, codec);
        }
    }

    /** The compute seam, shared with the context so counts are assertable. */
    private static final class CountingStats implements TopicStats {
        private final AtomicLong computes = new AtomicLong();

        @Override
        public TopicStatsReport compute(String topicId) {
            computes.incrementAndGet();
            return new TopicStatsReport(topicId, 1, 20, 20, 20, 20.0, "title");
        }

        @Override
        public long computeCount() {
            return computes.get();
        }
    }

    @Test
    void theContainerHandsOutTheProxyNotTheBareClass() {
        AnnotatedTopicStats proxy = context.getBean(AnnotatedTopicStats.class);
        assertTrue(proxy.getClass().getName().contains("$$"),
                "the bean must be a CGLIB proxy for the annotations to fire: "
                        + proxy.getClass().getName());
    }

    @Test
    void twoProxiedCallsComputeOnceTheCacheWorked() {
        AnnotatedTopicStats proxy = context.getBean(AnnotatedTopicStats.class);
        long before = SLOW.computeCount();

        proxy.reportJson("proxy-proof");
        proxy.reportJson("proxy-proof");

        assertEquals(before + 1, SLOW.computeCount(),
                "the second proxied call must be served from the cache");
    }

    @Test
    void theSelfInvocationTrapIsRealTwoBypassingCallsComputeTwice() {
        AnnotatedTopicStats proxy = context.getBean(AnnotatedTopicStats.class);
        long before = SLOW.computeCount();

        proxy.reportJsonBypassingProxy("trap-proof");
        proxy.reportJsonBypassingProxy("trap-proof");

        assertEquals(before + 2, SLOW.computeCount(),
                "this.reportJson is plain dispatch — the interceptor never ran, nothing cached");
    }

    @Test
    void bypassedCallsNeverFillTheCacheEither() {
        AnnotatedTopicStats proxy = context.getBean(AnnotatedTopicStats.class);
        long before = SLOW.computeCount();

        proxy.reportJsonBypassingProxy("fill-proof");
        proxy.reportJson("fill-proof");

        assertEquals(before + 2, SLOW.computeCount(),
                "the self-call stored nothing, so the proxied call after it is still a MISS");
    }

    @Test
    void evictThroughTheProxyDropsTheEntry() {
        AnnotatedTopicStats proxy = context.getBean(AnnotatedTopicStats.class);
        proxy.reportJson("evict-proof");
        long before = SLOW.computeCount();

        proxy.evict("evict-proof");
        proxy.reportJson("evict-proof");

        assertEquals(before + 1, SLOW.computeCount(),
                "@CacheEvict through the proxy evicted; the next call recomputed");
    }
}
