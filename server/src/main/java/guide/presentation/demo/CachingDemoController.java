package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.UnavailableException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.caching.ScenarioRoutingCacheBackend;
import guide.domain.caching.GetTopicStats;
import guide.domain.caching.InvalidateTopicStats;
import guide.domain.caching.StatsOutcome;
import guide.domain.caching.TopicStatsReport;
import guide.domain.posts.GetPost;
import guide.domain.posts.ListPosts;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 21 (caching) — the caching lab:
 *
 * - GET  /api/demo/caching/stats/{topicId}?mode=explicit|annotation
 *   → {result, computeTimeMs, cache: HIT|MISS, key, mode, backendMode}:
 *   the SAME expensive read through the EXPLICIT cache-aside wrapper or
 *   the @Cacheable proxy, reporting which path answered, its verdict, and
 *   the key it traveled under. Repeat the call: MISS becomes HIT and the
 *   compute count (not the clock) is the proof.
 * - POST /api/demo/caching/invalidate/{topicId} → both paths' keys, named.
 * - GET  /api/demo/caching/http-cache/{postId} → the HTTP caching specimen:
 *   ETag (weak, from the post's version) + Last-Modified (updatedAt) +
 *   Cache-Control: max-age=30 — and Spring's conditional-request handling
 *   turns a matching If-None-Match into a 304 with no body. (Note the
 *   sibling /http-cache/headers below: the LITERAL route wins over
 *   /{postId} — topic 07's pattern-specificity lesson, load-bearing here.)
 * - GET  /api/demo/caching/http-cache/headers → introspection: the exact
 *   headers the specimen WOULD set, and why each one exists.
 *
 * Thin by the house rule; injectFailure guards every endpoint first.
 */
@Tag(name = "Caching", description = "The caching topic's lab: cache-aside vs @Cacheable over one "
        + "backend, invalidation, and the HTTP caching specimen (ETag/304).")
@RestController
@RequestMapping("/api/demo/caching")
public class CachingDemoController {

    /** How long a shared cache may serve the specimen without revalidating. */
    static final long MAX_AGE_SECONDS = 30;

    /** One instrumented read through either caching path (the report flat, not nested). */
    record StatsView(String topicId, String mode, TopicStatsReport result, String cache,
            long computeTimeMs, String key, String backendMode) {
    }

    /** The invalidation act's receipt: every key that left the cache. */
    record InvalidateView(String topicId, List<String> evictedKeys) {
    }

    /** The post as the specimen serves it — the full representation a cache would hold. */
    record PostView(String id, String title, String body, List<String> tags, String status,
            long version, String createdAt, String updatedAt) {

        static PostView of(Post post) {
            return new PostView(post.id(), post.title(), post.body(), post.tags(),
                    post.status().wireName(), post.version(),
                    post.createdAt().toString(), post.updatedAt().toString());
        }
    }

    /** The teaching introspection: the headers, their values, their reasons, the round trip. */
    record HttpCacheHeadersView(String postId, Map<String, String> headers,
            Map<String, String> why, List<String> roundTrip) {
    }

    private final GetTopicStats getTopicStats;
    private final InvalidateTopicStats invalidateTopicStats;
    private final GetPost getPost;
    private final ListPosts listPosts;
    private final ScenarioStore scenarioStore;

    public CachingDemoController(GetTopicStats getTopicStats,
            InvalidateTopicStats invalidateTopicStats, GetPost getPost, ListPosts listPosts,
            ScenarioStore scenarioStore) {
        this.getTopicStats = getTopicStats;
        this.invalidateTopicStats = invalidateTopicStats;
        this.getPost = getPost;
        this.listPosts = listPosts;
        this.scenarioStore = scenarioStore;
    }

    @Operation(summary = "One topic's stats through either caching path", operationId = "getTopicStats",
            description = "mode=explicit runs the cache-aside wrapper over the CacheBackend port; "
                    + "mode=annotation runs the @Cacheable proxy over the same backend. The response "
                    + "reports the HIT/MISS verdict, the call's duration, the key used, and the "
                    + "backend's data mode — repeat the call to watch MISS become HIT.")
    @GetMapping(value = "/stats/{topicId}", produces = "application/json")
    ResponseEntity<StatsView> stats(@PathVariable String topicId,
            @RequestParam(required = false) String mode) {
        requireAvailable();
        Result<GetTopicStats.Mode> parsedMode = GetTopicStats.Mode.parse(mode);
        return switch (parsedMode) {
            case Success<GetTopicStats.Mode> ok -> statsResponse(topicId, ok.value());
            case Failed<GetTopicStats.Mode> failed -> throw AppException.of(failed.failure());
        };
    }

    @Operation(summary = "Drop both caching paths' entries for a topic", operationId = "invalidateTopicStats",
            description = "The explicit invalidation act: evicts the cache-aside key AND the "
                    + "@Cacheable entry, and answers with every key it dropped — an eviction you "
                    + "cannot name did not happen.")
    @PostMapping(value = "/invalidate/{topicId}", produces = "application/json")
    ResponseEntity<InvalidateView> invalidate(@PathVariable String topicId) {
        requireAvailable();
        return switch (invalidateTopicStats.execute(topicId)) {
            case Success<InvalidateTopicStats.Evicted> ok -> ResponseEntity.ok(
                    new InvalidateView(ok.value().topicId(), ok.value().keys()));
            case Failed<InvalidateTopicStats.Evicted> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * The HTTP caching specimen. The validators are CHEAP to compute — the
     * weak ETag is the post's version, Last-Modified its updatedAt — and
     * Spring MVC performs the conditional dance automatically: when the
     * request carries a matching If-None-Match (or If-Modified-Since), the
     * adapter answers 304 with no body. Edit the post (version +1) and the
     * validator changes; the next revalidation answers 200 with new bytes.
     */
    @Operation(summary = "The HTTP caching specimen: validators + Cache-Control, 304 on revalidation",
            operationId = "httpCacheGetPost",
            description = "Serves the post with ETag (weak, from the version), Last-Modified "
                    + "(updatedAt) and Cache-Control: max-age=30. Send If-None-Match with the "
                    + "received ETag to watch the 304 round trip.")
    @GetMapping(value = "/http-cache/{postId}", produces = "application/json")
    ResponseEntity<PostView> httpCache(@PathVariable String postId) {
        requireAvailable();
        return switch (getPost.execute(postId)) {
            case Success<Post> ok -> {
                Post post = ok.value();
                yield ResponseEntity.ok()
                        .eTag(weakEtag(post.version()))
                        .lastModified(post.updatedAt())
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(MAX_AGE_SECONDS)))
                        .body(PostView.of(post));
            }
            case Failed<Post> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * The introspection endpoint: the exact headers /http-cache/{postId}
     * would set, computed against a real post (the newest one), with the
     * reason each header exists and the revalidation round trip spelled
     * out. The literal route beats /{postId} — pattern specificity, topic 07.
     */
    @Operation(summary = "The headers the specimen WOULD set, and why", operationId = "httpCacheHeaders",
            description = "Teaching introspection: ETag, Last-Modified and Cache-Control with "
                    + "their concrete values for the newest post, each header's job, and the "
                    + "If-None-Match → 304 round trip in four steps.")
    @GetMapping(value = "/http-cache/headers", produces = "application/json")
    ResponseEntity<HttpCacheHeadersView> httpCacheHeaders() {
        requireAvailable();
        return switch (listPosts.execute(0, 1, PostSort.DEFAULT, PostFilter.NONE)) {
            // defensive: page 0, size 1 cannot fail validation — a Failed here means
            // the listing itself is down, and the taxonomy answers it.
            case Success<PagedPosts> ok -> ResponseEntity.ok(headersView(ok.value().items().getFirst()));
            case Failed<PagedPosts> failed -> throw AppException.of(failed.failure());
        };
    }

    private HttpCacheHeadersView headersView(Post exhibit) {
        return new HttpCacheHeadersView(exhibit.id(),
                Map.of(
                        "ETag", weakEtag(exhibit.version()),
                        "Last-Modified", RFC_1123.format(exhibit.updatedAt().atZone(ZoneOffset.UTC)),
                        "Cache-Control", "max-age=" + MAX_AGE_SECONDS),
                Map.of(
                        "ETag", "a WEAK validator (W/) from the post's version — cheap to compute, "
                                + "changes on every edit; weak because byte-identical serialization "
                                + "is not promised, semantic equality is",
                        "Last-Modified", "the time validator, second choice: precise to the second, "
                                + "useless for two writes inside one — the ETag carries version "
                                + "granularity this seed never needs but real corpora do",
                        "Cache-Control", "max-age=30 lets shared caches serve without asking; the "
                                + "validators make the eventual revalidation one cheap 304 instead "
                                + "of a full body"),
                List.of(
                        "1. GET /http-cache/{postId} → 200 + the post + the three validators",
                        "2. GET again with If-None-Match: " + weakEtag(exhibit.version()),
                        "3. version unchanged → 304 Not Modified, validators only, NO body",
                        "4. edit the post (version +1) → the ETag changes → next revalidation is 200"));
    }

    // ---- edge helpers ----

    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /** The weak validator from the version: changes exactly when the content does. */
    private static String weakEtag(long version) {
        return "W/\"v" + version + "\"";
    }

    private ResponseEntity<StatsView> statsResponse(String topicId, GetTopicStats.Mode mode) {
        return switch (getTopicStats.execute(topicId, mode)) {
            case Success<StatsOutcome> ok -> {
                StatsOutcome outcome = ok.value();
                yield ResponseEntity.ok(new StatsView(topicId, mode.wireName(), outcome.result(),
                        outcome.cache().name(), outcome.computeTimeMs(), outcome.key(),
                        backendMode()));
            }
            case Failed<StatsOutcome> failed -> throw AppException.of(failed.failure());
        };
    }

    /** The caching area's backend mode as the wire spells it. */
    private String backendMode() {
        return scenarioStore.dataModeFor(ScenarioRoutingCacheBackend.AREA) == ScenarioDataMode.LIVE
                ? "live" : "fake";
    }

    /** ScenarioStore exhibit: with injectFailure on, the demo dependency is "down" — 503. */
    private void requireAvailable() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "caching demos are unavailable by scenario (injectFailure=true)",
                    Map.of("area", ScenarioRoutingCacheBackend.AREA));
        }
    }
}
