package guide.config;

import guide.data.caching.CacheBackendCacheManager;
import guide.data.caching.InMemoryCacheBackend;
import guide.data.caching.JacksonReportCodec;
import guide.data.caching.ScenarioRoutingCacheBackend;
import guide.domain.caching.AnnotatedTopicStats;
import guide.domain.caching.AnnotationPathStats;
import guide.domain.caching.CachedTopicStats;
import guide.domain.caching.Delayer;
import guide.domain.caching.GetTopicStats;
import guide.domain.caching.InvalidateTopicStats;
import guide.domain.caching.ReportCodec;
import guide.domain.caching.SlowTopicStats;
import guide.domain.caching.TopicStats;
import guide.domain.posts.ListPosts;
import guide.domain.redis.CacheBackend;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 21 (caching) wiring — the caching lab's composition root. THREE
 * decisions live here, all of them the topic's teaching:
 *
 * 1. @EnableCaching turns on Spring's cache abstraction — the proxy
 *    machinery @Cacheable/@CacheEvict ride on (and the self-invocation trap
 *    comes with it). NO NEW DEPENDENCY: the abstraction ships in
 *    spring-context (org.springframework.cache.*), which every Boot app
 *    already carries — spring-boot-starter-cache would only add the
 *    autoconfiguration this file does explicitly by declaring its own
 *    CacheManager bean.
 *
 * 2. ONE BACKEND, TWO DOORS: the explicit path (CachedTopicStats) and the
 *    annotation path (the CacheManager over the same router) share the
 *    CacheBackend port — fake in-memory by default, Redis on the scenario
 *    flip. The Redis adapter is topic 20's @Lazy cacheBackend bean
 *    (guide:cache: namespace), injected lazy so nothing Redis-shaped is
 *    constructed at boot — and NO eager consumers anywhere: this config
 *    registers no listener containers, no schedulers, no SmartLifecycle
 *    beans at all (topic 20's lesson: the lifecycle processor starts
 *    SmartLifecycle beans even through @Lazy — so none are registered).
 *
 * 3. The router bean is deliberately NOT @Primary: topic 20's live
 *    integration test autowires the CacheBackend by FIELD NAME, and the
 *    by-name tiebreak must keep resolving to the Redis adapter. Everything
 *    in this config injects the router explicitly by qualifier instead.
 */
@Configuration
@EnableCaching
public class CachingDemoConfig {

    /** The demo's safety-net TTL — explicit and annotation paths alike. */
    public static final Duration STATS_TTL = Duration.ofSeconds(60);

    /** The artificial expense: enough to feel in a demo, tolerable in a test. */
    public static final long COMPUTE_DELAY_MS = 150;

    /** The annotation path's one and only cache, named in the wiring like every other cache decision. */
    public static final String ANNOTATION_CACHE_NAME = AnnotatedTopicStats.CACHE_NAME;

    // ---- the backend: fake, live, and the router between them ----

    /** The fake backend of record — the boot-without-Redis answer, and the reset handle. */
    @Bean
    InMemoryCacheBackend inMemoryCacheBackend() {
        return new InMemoryCacheBackend();
    }

    /**
     * The caching area's router. The live side is topic 20's @Lazy
     * CacheBackend bean, and @Lazy on the parameter keeps even that lazy:
     * the router holds a resolution proxy, the adapter (and its
     * StringRedisTemplate) is constructed only when a LIVE call first
     * routes to it.
     */
    @Bean
    CacheBackend cachingCacheBackend(guide.core.scenario.ScenarioStore scenarioStore,
            InMemoryCacheBackend fake,
            @Lazy @Qualifier("cacheBackend") guide.domain.redis.CacheBackend liveRedis) {
        return new ScenarioRoutingCacheBackend(scenarioStore, fake, () -> liveRedis);
    }

    // ---- the expensive read and its codec ----

    /** The report codec — the autoconfigured Jackson 3 mapper (tools.jackson). */
    @Bean
    ReportCodec reportCodec(ObjectMapper mapper) {
        return new JacksonReportCodec(mapper);
    }

    /** The expensive read: the real posts substrate behind an artificial 150 ms pause. */
    @Bean
    TopicStats slowTopicStats(ListPosts listPosts) {
        return new SlowTopicStats(listPosts, Delayer.fixed(Duration.ofMillis(COMPUTE_DELAY_MS)));
    }

    // ---- path one: explicit cache-aside ----

    @Bean
    CachedTopicStats cachedTopicStats(
            @Qualifier("cachingCacheBackend") guide.domain.redis.CacheBackend backend,
            TopicStats slow, ReportCodec codec) {
        return new CachedTopicStats(backend, slow, codec);
    }

    // ---- path two: the annotation abstraction ----

    /**
     * The annotated service. The cache infrastructure (turned on by
     * @EnableCaching above) wraps THIS bean in a proxy — inject it
     * anywhere and the advice runs; call it from itself and it does not
     * (the trap, test-proven downstream).
     */
    @Bean
    AnnotatedTopicStats annotatedTopicStats(TopicStats slow, ReportCodec codec) {
        return new AnnotatedTopicStats(slow, codec);
    }

    /** The annotation path's instrumentation — diffs the compute COUNT around the proxy call. */
    @Bean
    AnnotationPathStats annotationPathStats(AnnotatedTopicStats cachePoint, TopicStats slow,
            ReportCodec codec) {
        return new AnnotationPathStats(cachePoint, slow, codec);
    }

    /**
     * The CacheManager the annotation path talks to: OUR port-backed
     * manager, one cache declared (topicStats, TTL 60 s), everything not
     * named falls back to the default TTL. Declaring the bean here is what
     * makes the abstraction ours instead of Boot's fallback
     * ConcurrentMapCacheManager (which never expires and lives in-process
     * — both disqualifying for a shared cache).
     */
    @Bean
    CacheManager cacheManager(
            @Qualifier("cachingCacheBackend") guide.domain.redis.CacheBackend backend,
            ObjectMapper mapper) {
        return new CacheBackendCacheManager(backend,
                Map.of(ANNOTATION_CACHE_NAME, STATS_TTL), STATS_TTL, mapper);
    }

    // ---- use cases ----

    /** One read, either caching path — the use-case-level switch. */
    @Bean
    GetTopicStats getTopicStats(CachedTopicStats explicit, AnnotationPathStats annotation) {
        return new GetTopicStats(explicit, annotation);
    }

    /** The deliberate invalidation act, both paths at once. */
    @Bean
    InvalidateTopicStats invalidateTopicStats(CachedTopicStats explicit,
            AnnotationPathStats annotation) {
        return new InvalidateTopicStats(explicit, annotation);
    }

    // The HTTP-caching specimen (ETag/Last-Modified/Cache-Control) needs no
    // wiring of its own: CachingDemoController injects the EXISTING GetPost
    // and ListPosts use cases (PostsDemoConfig) and the response headers
    // are the presentation edge's own work.
}
