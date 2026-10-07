package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.ErrorResponse;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.errors.UnavailableException;
import guide.core.logging.RequestIdFilter;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioStore;
import guide.domain.posts.CreatePost;
import guide.domain.posts.DeletePost;
import guide.domain.posts.GetPost;
import guide.domain.posts.ListPosts;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import guide.domain.posts.PostStatus;
import guide.domain.posts.UpdatePost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 11 (api-design) — the posts resource, done the way
 * the whole topic teaches it. One collection, five verbs, the full status
 * matrix, and a /v2/ sibling that teaches versioning by being a thin mapping
 * over the SAME use cases:
 *
 * - GET    /api/demo/api/posts?page&size&sort&status&tag → 200 PagedPosts
 * - GET    /api/demo/api/posts/{id}                      → 200 Post + ETag | 404
 * - POST   /api/demo/api/posts                           → 201 + Location + ETag | 422
 * - PUT    /api/demo/api/posts/{id}  (If-Match|version)  → 200 + ETag | 404 | 409 | 422
 * - DELETE /api/demo/api/posts/{id}                      → 204 | 404
 * - GET    /api/demo/api/v2/posts[/{id}]                 → v2 = v1 + summary field
 *
 * THE 400 vs 422 RULE THIS EDGE HOLDS: failures that mean "the request could
 * not even be read" (unreadable JSON, wrong types) stay 400 via the global
 * handler; failures that mean "read fine, judged unacceptable" (title 121
 * chars, bogus status, missing version) answer 422 — semantic validation,
 * this edge's one deliberate mapping decision, rendered in the same envelope
 * as every other failure.
 *
 * ScenarioStore exhibit: with injectFailure on, every endpoint below answers
 * the 503 envelope — one guard, checked first, so demos can show clients
 * meeting a genuinely degraded dependency.
 *
 * OpenAPI curation (topic 12): this controller is the guide's DOCUMENTED
 * resource, so its annotations are the deliberate budget — a tag, one
 * operation per handler with a STABLE operationId (client generators key on
 * these; method-name-derived ids silently rename on every refactor), and
 * @ApiResponse declarations that restore what ResponseEntity<?> erases: the
 * generator cannot see a schema through a wildcard return, so the two lines
 * per handler below are what put Post and PagedPosts into components/schemas.
 */
@Tag(name = "Posts", description = "The api-design topic's resource: full CRUD with filtering, "
        + "pagination, and optimistic concurrency (If-Match/ETag).")
@RestController
@RequestMapping("/api/demo/api")
public class ApiDesignDemoController {

    /** Where created posts are announced: the v1 collection — Location's noun. */
    static final String COLLECTION_PATH = "/api/demo/api/posts";

    /** Accepts the ETag spellings this API emits: W/"3", "3", or 3. */
    private static final Pattern VERSIONED_ETAG = Pattern.compile("^(?:W/)?\"?(\\d+)\"?$");

    private final ListPosts listPosts;
    private final GetPost getPost;
    private final CreatePost createPost;
    private final UpdatePost updatePost;
    private final DeletePost deletePost;
    private final ScenarioStore scenarioStore;

    public ApiDesignDemoController(ListPosts listPosts, GetPost getPost, CreatePost createPost,
            UpdatePost updatePost, DeletePost deletePost, ScenarioStore scenarioStore) {
        this.listPosts = listPosts;
        this.getPost = getPost;
        this.createPost = createPost;
        this.updatePost = updatePost;
        this.deletePost = deletePost;
        this.scenarioStore = scenarioStore;
    }

    // ---- v1: the collection ----

    /**
     * The listing: filtering, sorting, paging — every knob a query param, every value validated.
     */
    @Operation(summary = "List posts — paged, filtered, sorted",
            operationId = "listPosts",
            description = "Defaults: page=0, size=10, sort=createdAt,desc. Bogus sort/status/tag "
                    + "values answer the 422 validation envelope, never a 500.",
            responses = @ApiResponse(responseCode = "200", description = "One page of the collection",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PagedPosts.class))))
    @GetMapping(value = "/posts", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            HttpServletRequest request) {
        requireAvailable();
        Result<PostFilter> filter = PostFilter.of(status, tag);
        if (filter instanceof Failed<PostFilter> failed) {
            return unprocessable(failed.failure(), request);
        }
        Result<PostSort> order = PostSort.parse(sort);
        if (order instanceof Failed<PostSort> failed) {
            return unprocessable(failed.failure(), request);
        }
        return switch (listPosts.execute(page, size, value(order), value(filter))) {
            case Success<PagedPosts> success -> ResponseEntity.ok(success.value());
            case Failed<PagedPosts> failed -> {
                if (failed.failure().kind() == FailureKind.VALIDATION) {
                    yield unprocessable(failed.failure(), request);
                }
                throw AppException.of(failed.failure());
            }
        };
    }

    /**
     * One post, versioned: the ETag header IS the version field — the two spell one number.
     */
    @Operation(summary = "Read one post",
            operationId = "getPost",
            description = "The weak ETag W/\"<version>\" is the optimistic-concurrency token "
                    + "If-Match hands back on update.",
            responses = @ApiResponse(responseCode = "200", description = "The post's current representation",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Post.class))))
    @GetMapping(value = "/posts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Post> get(@PathVariable String id) {
        requireAvailable();
        return switch (getPost.execute(id)) {
            case Success<Post> success -> ResponseEntity.ok()
                    .header(HttpHeaders.ETAG, etagOf(success.value()))
                    .body(success.value());
            case Failed<Post> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * Creation: 201 + Location naming the new resource + the ETag of version 1.
     */
    @Operation(summary = "Create a post",
            operationId = "createPost",
            description = "201 with Location naming the created resource and its version-1 ETag; "
                    + "semantic failures (title over 120 chars, unknown status) answer 422.",
            responses = @ApiResponse(responseCode = "201", description = "Created — see Location and ETag",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Post.class))))
    @PostMapping(value = "/posts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(@RequestBody CreatePostRequest body, HttpServletRequest request) {
        requireAvailable();
        Result<PostDraft> draft = PostDraft.of(body.title(), body.body(), body.tags(), body.status());
        if (draft instanceof Failed<PostDraft> failed) {
            return unprocessable(failed.failure(), request);
        }
        return switch (createPost.execute(value(draft))) {
            case Success<Post> success -> ResponseEntity
                    .created(URI.create(COLLECTION_PATH + "/" + success.value().id()))
                    .header(HttpHeaders.ETAG, etagOf(success.value()))
                    .body(success.value());
            case Failed<Post> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * Replacement under optimistic concurrency: the caller must state the
     * version it read — If-Match header (ETag spelling) or a version field in
     * the body; disagreement with the stored version is a 409 that names the
     * current one, so the client can re-read and retry.
     */
    @Operation(summary = "Replace a post under optimistic concurrency",
            operationId = "updatePost",
            description = "Full replacement. State the version you read via If-Match or a body "
                    + "version field; a stale version answers 409 naming the current one.",
            responses = @ApiResponse(responseCode = "200", description = "Replaced — ETag carries the new version",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Post.class))))
    @PutMapping(value = "/posts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> update(@PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdatePostRequest body,
            HttpServletRequest request) {
        requireAvailable();
        Result<Long> expected = expectedVersion(ifMatch, body.version());
        if (expected instanceof Failed<Long> failed) {
            return unprocessable(failed.failure(), request);
        }
        Result<PostDraft> draft = PostDraft.of(body.title(), body.body(), body.tags(), body.status());
        if (draft instanceof Failed<PostDraft> failed) {
            return unprocessable(failed.failure(), request);
        }
        return switch (updatePost.execute(id, value(expected), value(draft))) {
            case Success<Post> success -> ResponseEntity.ok()
                    .header(HttpHeaders.ETAG, etagOf(success.value()))
                    .body(success.value());
            case Failed<Post> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * Deletion: 204 says it happened; a repeat answers 404 — deletion is idempotent, not silent.
     */
    @Operation(summary = "Delete a post", operationId = "deletePost",
            description = "204 on success; replaying the DELETE answers 404 — the effect is "
                    + "stable, the observation differs.")
    @DeleteMapping("/posts/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        requireAvailable();
        return switch (deletePost.execute(id)) {
            case Success<Void> ignored -> ResponseEntity.noContent().build();
            case Failed<Void> failed -> throw AppException.of(failed.failure());
        };
    }

    // ---- v2: same use cases, one added field — versioning as a mapping ----

    /**
     * The v2 collection: identical query contract, every post carrying the added summary field.
     */
    @Operation(summary = "List posts, v2 representation (+summary)", operationId = "listPostsV2",
            description = "Versioning as a mapping: same use cases, same query contract, one "
                    + "added field — additive evolution in one route.")
    @GetMapping(value = "/v2/posts", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> listV2(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            HttpServletRequest request) {
        ResponseEntity<?> v1 = list(page, size, sort, status, tag, request);
        return v1.getStatusCode().is2xxSuccessful()
                ? ResponseEntity.ok(PagedV2Posts.from((PagedPosts) v1.getBody()))
                : v1;
    }

    /**
     * The v2 single resource: GetPost unchanged, the representation gains summary.
     */
    @Operation(summary = "Read one post, v2 representation (+summary)", operationId = "getPostV2")
    @GetMapping(value = "/v2/posts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> getV2(@PathVariable String id) {
        requireAvailable();
        return switch (getPost.execute(id)) {
            case Success<Post> success -> ResponseEntity.ok(V2Post.from(success.value()));
            case Failed<Post> failed -> throw AppException.of(failed.failure());
        };
    }

    // ---- wire DTOs: the body shapes, bound (and quoted) at the edge that owns them ----

    /**
     * Creation body: all-nullable (Jackson leaves absent fields null) — PostDraft.of judges it.
     */
    @Schema(description = "Creation body — every field optional at binding; PostDraft.of judges "
            + "semantics and answers 422 with per-field reasons. Unknown fields are tolerated "
            + "and dropped (additive-only evolution).")
    record CreatePostRequest(String title, String body, List<String> tags, String status) {
    }

    /**
     * Replacement body: the same fields plus the version this update expects.
     */
    @Schema(description = "Replacement body — plus the version this update expects (or send "
            + "If-Match: W/\"<version>\" instead).")
    record UpdatePostRequest(String title, String body, List<String> tags, String status, Long version) {
    }

    /** The v2 representation: v1's fields plus summary — additive evolution, the whole trick. */
    record V2Post(String id, String title, String summary, String body, List<String> tags,
            PostStatus status, long version, Instant createdAt, Instant updatedAt) {

        static V2Post from(Post post) {
            return new V2Post(post.id(), post.title(), summarize(post.body()), post.body(),
                    post.tags(), post.status(), post.version(), post.createdAt(),
                    post.updatedAt());
        }

        /** First 140 characters on a word-ish boundary, ellipsized — v2's one added field. */
        static String summarize(String body) {
            String flat = body.strip();
            if (flat.length() <= 140) {
                return flat;
            }
            return flat.substring(0, 140).stripTrailing() + "…";
        }
    }

    /** The v2 collection answer: the same paging metadata around mapped items. */
    record PagedV2Posts(List<V2Post> items, int page, int size, long totalElements, int totalPages) {

        static PagedV2Posts from(PagedPosts page) {
            return new PagedV2Posts(page.items().stream().map(V2Post::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }

    // ---- edge helpers: the Result→HTTP mapping decisions, all in one place ----

    /**
     * The 422 decision: a VALIDATION failure from posts parsing answers here
     * with the semantic status — same envelope, same canonical keys, one
     * status more precise than the generic 400 the global handler uses.
     */
    private static ResponseEntity<ErrorResponse> unprocessable(Failure failure,
            HttpServletRequest request) {
        return ResponseEntity.status(422)
                .body(ErrorResponse.of(failure.kind().errorCode(), failure.message(),
                        requestId(request), failure.details()));
    }

    /** If-Match wins when present (the ETag spelling); otherwise the body's version field. */
    private static Result<Long> expectedVersion(String ifMatch, Long bodyVersion) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            Matcher matcher = VERSIONED_ETAG.matcher(ifMatch.strip());
            if (!matcher.matches()) {
                return Result.failed(Failure.of(FailureKind.VALIDATION,
                        "If-Match must quote the version you read, e.g. W/\"3\"",
                        Map.of("fields", Map.of("ifMatch", "must be W/\"<version>\" or \"<version>\""))));
            }
            return Result.success(Long.parseLong(matcher.group(1)));
        }
        if (bodyVersion == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "an update must state the version it expects",
                    Map.of("fields", Map.of("version",
                            "required — send the version you read, or an If-Match header"))));
        }
        return Result.success(bodyVersion);
    }

    /** The version as a weak ETag: what GET hands out, If-Match hands back. */
    private static String etagOf(Post post) {
        return "W/\"" + post.version() + "\"";
    }

    /** ScenarioStore exhibit: with injectFailure on, this resource is "down" — every verb answers 503. */
    private void requireAvailable() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "posts API is unavailable by scenario (injectFailure=true)",
                    Map.of("area", PostRepository.AREA));
        }
    }

    private static <T> T value(Result<T> result) {
        if (result instanceof Success<T> success) {
            return success.value();
        }
        throw new IllegalStateException("value() on a Failed result — handle the failure first");
    }

    /** The request id the filter stamped — same fallback chain as the advice's. */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isEmpty()) {
            return value;
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }
}
