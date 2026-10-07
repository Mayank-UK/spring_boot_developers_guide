package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 11 — api-design. Group apis, order 1. The canonical resource
 * substrate: what REST actually is (and what 99% of APIs honestly are),
 * designing resources as nouns, the verb/status contract, URL surface
 * (pagination, filtering, sorting), one error envelope everywhere versus
 * RFC 9457, versioning by route, and one request traced through the whole
 * pipeline — all against the REAL posts resource this topic builds in
 * guide.domain.posts / guide.data.posts and serves at /api/demo/api/posts.
 */
public final class ApiDesignTopic {

    private ApiDesignTopic() {
    }

    public static Topic topic() {
        return new Topic("api-design", "API design",
                "The posts resource, built once and built to last: what REST actually "
                        + "is, resources as nouns, the verb/status contract, URLs as API surface, "
                        + "one error envelope everywhere, and versioning without rewriting — the "
                        + "substrate the documentation, GraphQL, and database topics all stand on.",
                TopicGroupId.APIS, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-rest-actually-is", "What REST actually is",
                        "Fielding in five minutes: resources, representations, and the uniform interface — plus the honesty about what most \"REST APIs\" really are.",
                        text("rest:fielding", """
                                REST is not a protocol, a library, or JSON over HTTP. It is an ARCHITECTURAL STYLE, named by Roy Fielding in his 2000 dissertation to describe how the web itself works — and its vocabulary is worth learning precisely, because every argument about "RESTful" design is an argument about these five words. A RESOURCE is anything worth identifying: a post, a user, the collection of published posts. It is a CONCEPTUAL target, not a file or a row. A REPRESENTATION is the bytes you actually exchange ABOUT the resource — the JSON document, its headers, its ETag. GET /api/posts/42 does not return the post; it returns A representation of the post, and that distinction is load-bearing: the same post can have a v1 representation and a v2 one, a JSON one and an XML one, and it remains one resource throughout. The UNIFORM INTERFACE is the set of rules shared by every resource: identification by URI, manipulation through representations (you change a resource by sending a representation of what you want it to be), self-descriptive messages (the method, status, and content-type carry the meaning), and hypermedia as the engine of application state — HATEOAS, the constraint that responses tell the client what it can do next by linking to the allowed transitions."""),
                        text("rest:honesty", """
                                Now the honesty, because this is where most confusion lives: 99% of the APIs called "REST" — including this guide's — drop HATEOAS entirely. They are HTTP JSON APIs at level 2 of the Richardson maturity model: resources with nouns, verbs with their HTTP meanings, status codes used correctly — but no hypermedia controls; the client knows the URLs by convention (or by an OpenAPI document, next topic). That is a legitimate engineering trade, not a failure: hypermedia buys client evolvability for clients that follow links dynamically, at the cost of chatty interactions and a style of client almost nobody writes for internal APIs. What you must NOT do is drop the constraints that are load-bearing for every API. STATELESSNESS: every request carries everything the server needs — no server-side session affinity, which is what lets the scaling topic put three replicas behind one load balancer and answer any request on any of them. CACHEABILITY: GET is safe, responses declare their cache semantics, and conditional headers (ETag/If-None-Match) make revalidation cheap — the caching topic builds on exactly this. And the uniform interface itself: fixed verb meanings and one error shape everywhere, which is the contract that lets generic clients, proxies, and YOUR OWN error boundary work across every endpoint you will ever ship."""),
                        text("rest:this-guide", """
                                This topic makes those three earn-their-keep constraints concrete on one real resource. The posts substrate in guide.domain.posts is a full domain slice — entity with invariants, port, five Result-typed use cases — served by a thin controller at /api/demo/api/posts with the whole status matrix, optimistic concurrency via ETag/If-Match, and one error envelope on every failure path. It is deliberately built to LAST: the api-documentation topic generates an OpenAPI surface from these very endpoints, the graphql topic mounts a schema over the same use cases, and the data topics swap the storage behind the same port. When you read a later topic and wonder "where did this resource come from, and why is it designed this way" — the answer is here.""")),
                section("designing-resources", "Designing resources",
                        "Nouns, not verbs: what a resource is, how collections nest, and the posts substrate as the specimen.",
                        text("resources:nouns", """
                                The first rule of resource design: URLS NAME THINGS, THEY DO NOT CALL FUNCTIONS. /api/demo/api/posts names a collection — a thing that exists, can be listed, can grow. /api/createPost?title=... names a procedure wearing a URL costume, and it gives nothing back: no address for the created post, no verb the HTTP infrastructure understands, no way to cache, filter, or paginate. The test is grammatical: if the URL reads as a noun phrase (the posts, post 42, the published posts, user 42's posts), it is a resource; if it reads as a sentence (create post, fetch posts, send reset email), it is an RPC endpoint — sometimes the right tool (the webhooks topic's receiving endpoint is deliberately one), but never a resource. Collections are plural by convention (/posts, not /post), and an item is the collection plus its identity: /posts/9f1c5a52-...."""),
                        text("resources:granularity", """
                                Nesting and granularity are the second decision. /users/42/posts expresses OWNERSHIP: the posts belong to exactly one user, the URL says whose, and a 404 on the user cascades naturally. /posts?author=42 expresses an INTERSECTION: posts filtered by an author among other filters, composable with status, tag, and paging. The rule of thumb this guide follows: nest when the child cannot exist outside the parent AND the parent is part of the child's identity; query when the relationship is a filter over an independently meaningful collection. And rarely nest deeper than two levels — /users/42/posts/9f.../comments/replies/17 is a URL doing a query's job. The posts substrate ships ONE collection with no parent, so it needs no nesting — but its filter design (?status=&tag=) is built exactly for the day a parent or another axis appears."""),
                        text("resources:specimen", """
                                Now the specimen itself — because "design a resource" bottoms out in two contracts. The DATA contract is the entity: a post is an immutable record whose compact constructor refuses invalid state at the only door that matters, construction. The same title/body rules the edge validates for 422 live here as invariants, so an internal caller, a batch job, or a future adapter cannot build a bad post any more than an HTTP client can send one:"""),
                        code("resources:entity", "java",
                                "guide/domain/posts/Post.java — the resource's data contract: invariants at the door, version arithmetic in exactly one place",
                                "server/src/main/java/guide/domain/posts/Post.java", """
                                public record Post(String id, String title, String body, List<String> tags, PostStatus status,
                                        long version, Instant createdAt, Instant updatedAt) {

                                    public Post {
                                        if (id == null || id.isBlank()) {
                                            throw new IllegalArgumentException("post id must be a non-blank string");
                                        }
                                        if (PostDraft.titleProblem(title) != null) {
                                            throw new IllegalArgumentException("post title " + PostDraft.titleProblem(title));
                                        }
                                        // …
                                        if (updatedAt.isBefore(createdAt)) {
                                            throw new IllegalArgumentException("post updatedAt cannot precede createdAt");
                                        }
                                        if (version < 1) {
                                            throw new IllegalArgumentException("post version starts at 1 and only grows");
                                        }
                                        title = title.strip();
                                        body = body.strip();
                                    }

                                    /** The next revision of this post: same identity and createdAt, content replaced, version +1. */
                                    public Post revised(PostDraft draft, Instant now) {
                                        Objects.requireNonNull(draft, "draft");
                                        return new Post(id, draft.title(), draft.body(), draft.tags(), draft.status(),
                                                version + 1, createdAt, now);
                                    }
                                }"""),
                        text("resources:port", """
                                The ACCESS contract is the port: what the resource's storage must be able to answer, stated in the domain's language, with no storage technology in sight. Note the update signature — it takes the version the caller read and answers a TRI-STATE outcome, because "no such post" and "someone changed it first" are different facts that map onto different statuses. An Optional<Post> return would erase exactly the distinction the wire needs:"""),
                        code("resources:repository", "java",
                                "guide/domain/posts/PostRepository.java — the resource's access contract: five operations, no storage in sight",
                                "server/src/main/java/guide/domain/posts/PostRepository.java", """
                                public interface PostRepository {

                                    String AREA = "api";

                                    PagedPosts list(int page, int size, PostSort sort, PostFilter filter);

                                    Optional<Post> findById(String id);

                                    Post create(PostDraft draft);

                                    Update update(String id, long expectedVersion, PostDraft draft);

                                    boolean delete(String id);

                                    // … the sealed Update: Updated(post) | NotFound | StaleVersion(currentVersion)
                                }"""),
                        text("resources:representation-again", """
                                And with the substrate in hand, the section's opening distinction pays off: what HTTP exchanges are REPRESENTATIONS of the resource, produced from the Post behind the port. Creation input is judged by PostDraft — the creation single source of truth whose of() factory turns a hostile body into per-field reasons instead of a stack trace. That factory is also the model for the 422 story three sections down. One resource, many representations, one set of invariants — the entire versioning section is a corollary of this sentence.""")),
                section("methods-and-status-codes", "Methods and status codes",
                        "Each verb's contract on /posts — safety, idempotency, and the difference between 200, 201, 202, and 204.",
                        text("methods:verbs", """
                                Every verb carries TWO independent promises, and conflating them is the classic API bug. SAFETY: the request changes nothing observable — GET and HEAD are safe, which is why crawlers, prefetchers, and your browser's refresh can issue them freely. IDEMPOTENCY: replaying the request leaves the resource in the same state as issuing it once — GET, PUT, DELETE, HEAD and OPTIONS; networks retry idempotent verbs without fear. POST is neither: two identical POSTs create two posts. That is not a flaw to apologize for; it is a property to design around — payment APIs take an Idempotency-Key header so retries of the SAME logical creation dedupe, and the reliability topic meets exactly this on message consumers. On our substrate: GET /posts and /posts/{id} are safe and idempotent; PUT /posts/{id} is idempotent GIVEN the version discipline (replaying the winning PUT hits 409 on the stale version, which is the honest idempotent answer — the resource did not change again); DELETE /posts/{id} removes, and its replay answers 404: the effect is stable, the observation differs. PUT as FULL REPLACEMENT (the whole content arrives, from the PostDraft up) is what keeps it idempotent; PATCH exists for partial updates, and honest survey: PATCH's body semantics must be specified (JSON Merge Patch, JSON Patch) or you have invented a second, vaguer PUT — this substrate chooses full-replace PUT and pays the bytes, deliberately."""),
                        text("methods:statuses", """
                                Statuses are the contract's vocabulary, and the row that matters most is 200 vs 201 vs 202 vs 204. 200 OK with a body: here is the representation you asked for. 201 Created: a new resource now exists — and the response MUST say where, in the Location header, because a client that cannot find what it just created will immediately GET the collection to look for it. 202 Accepted: understood and scheduled, NOT done — the honest answer when work continues without the request (the background-jobs topic's publishing pipeline: accept the draft, ship the newsletters later; answering 201 there would be a lie about work that has not happened). 204 No Content: done, deliberately bodyless — our DELETE. Creation on this substrate, exactly as served:"""),
                        code("methods:create", "java",
                                "guide/presentation/demo/ApiDesignDemoController.java — 201 + Location + ETag: creation's complete answer",
                                "server/src/main/java/guide/presentation/demo/ApiDesignDemoController.java", """
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
                                }"""),
                        text("methods:matrix", """
                                The full matrix this substrate emits — the whole topic in one table, and the assertions in ApiDesignDemoControllerTest hold every row:"""),
                        code("methods:matrix-wire", "http", "the status matrix the posts substrate emits (asserted end to end by its MockMvc suite)", null, """
                                GET    /api/demo/api/posts?…            → 200 {items,page,size,totalElements,totalPages}
                                GET    /api/demo/api/posts/{id}         → 200 post + ETag: W/"<version>"  | 404 envelope
                                POST   /api/demo/api/posts              → 201 + Location: /api/demo/api/posts/{id} + ETag
                                                                      → 422 envelope (semantic)          | 400 (unreadable)
                                PUT    /api/demo/api/posts/{id}         → 200 + ETag  | 409 envelope {expected,current}
                                                                      → 404 envelope | 422 (no version / bad draft)
                                DELETE /api/demo/api/posts/{id}         → 204          | 404 envelope
                                ANY    (injectFailure=true)             → 503 envelope {area:"api"}"""),
                        text("methods:concurrency", """
                                One row deserves its own paragraph: the 409. Optimistic concurrency is a status-code decision as much as a storage one — two writers read version 3, both PUT, and the second must learn it lost. The port makes losing an explicit outcome (StaleVersion carries the current version), the adapter enforces it atomically, and the wire names both numbers so the client can re-read and retry:"""),
                        code("methods:optimistic", "java",
                                "guide/data/posts/InMemoryPostRepository.java — the version check is atomic with the replace: one monitor, no locks held across requests",
                                "server/src/main/java/guide/data/posts/InMemoryPostRepository.java", """
                                @Override
                                public Update update(String id, long expectedVersion, PostDraft draft) {
                                    synchronized (mutationMonitor) {
                                        Post current = id == null ? null : postsById.get(id);
                                        if (current == null) {
                                            return new NotFound();
                                        }
                                        if (current.version() != expectedVersion) {
                                            return new StaleVersion(current.version());
                                        }
                                        Post revised = current.revised(draft, Instant.now());
                                        postsById.put(id, revised);
                                        return new Updated(revised);
                                    }
                                }""")),
                section("url-design-pagination-filtering", "URL design: pagination and filtering",
                        "The query string is contract surface: cursor vs offset honesty, sort syntax, filter design.",
                        text("urls:surface", """
                                The URL is the API. Not the docs, not the SDK — the URL is what clients actually construct, what appears in logs and support tickets, what ops reads aloud over the phone. Every token of the query string is therefore CONTRACT: its name, its type, its default, and its error behavior when the value is hostile. ?page=0&size=20&sort=title,asc&status=published&tag=rest — five parameters, five decisions. Defaults must be DOCUMENTED because clients rely on them within the hour (ours: page 0, size 10, sort createdAt descending, no filter). Values must be VALIDATED exactly like bodies: this API answers a bogus sort or status with the same validation envelope as a bad body — a 500 from a ?sort=typo is a contract hole. And names must be CONSISTENT across every collection the platform serves, because a client that learns ?page on posts expects ?page everywhere. The listing handler — every knob one query param, every value judged:"""),
                        code("urls:list", "java",
                                "guide/presentation/demo/ApiDesignDemoController.java — the listing: typed parsers validate the URL space, then one use case",
                                "server/src/main/java/guide/presentation/demo/ApiDesignDemoController.java", """
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
                                    // … listPosts.execute(page, size, order, filter) → 200 with the paged answer
                                }"""),
                        text("urls:sort-syntax", """
                                Sort syntax is a small design pattern of its own: FIELD,DIRECTION, one field, direction optional, default ascending — ?sort=title, ?sort=title,asc, ?sort=updatedAt,desc. The spelling is validated, not trusted: an unknown field is a per-field validation failure naming the legal values, because "author" failing with a stack trace is how clients learn to fear your API. The parser is a value object's job — PostSort owns its own wire spelling and hands back either a typed order or a Failed Result — and the tie-break matters more than it looks: sorting by createdAt alone is NONDETERMINISTIC whenever two posts share a timestamp, so every comparator ends with a then-by-id. Deterministic order is not a nicety; pagination without it shows the same row on two pages:"""),
                        code("urls:sort-parse", "java",
                                "guide/domain/posts/PostSort.java — the value object owns its wire spelling: parse judges, comparator ends with the id tie-break",
                                "server/src/main/java/guide/domain/posts/PostSort.java", """
                                public static Result<PostSort> parse(String wire) {
                                    if (wire == null || wire.isBlank()) {
                                        return Result.success(DEFAULT);
                                    }
                                    String[] parts = wire.strip().split(",", 2);
                                    Field field = fieldFor(parts[0]);
                                    if (field == null) {
                                        return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                "sort " + FIELDS_HELP, Map.of("fields", Map.of("sort", FIELDS_HELP))));
                                    }
                                    // … direction defaults to asc; "desc" flips it; anything else fails validation
                                    return Result.success(new PostSort(field, descending));
                                }"""),
                        text("urls:paging-honesty", """
                                Pagination is where honesty is most expensive and most worth it. OFFSET paging (page/size) is what this API teaches: trivial to implement, jumpable ("go to page 7"), and quietly dishonest at the edges — rows inserted between your fetches shift offsets, so page 3 can repeat page 2's rows. CURSOR paging (?after=9f1c5a52… — "give me what sorts strictly after this key") costs more to build and cannot jump, and buys stable pages under concurrent writes. The decision this API makes and documents: offset, with a stable deterministic sort and a documented ceiling on size (100), because for a curated guide the shift risk is cosmetic. A feed with heavy concurrent writes should choose cursors. Either way the METADATA must be honest — items plus page, size, totalElements, and totalPages derived, never hand-maintained, so the numbers cannot disagree:"""),
                        code("urls:paged", "java",
                                "guide/domain/posts/PagedPosts.java — computed metadata: totalPages is derived, so it cannot lie",
                                "server/src/main/java/guide/domain/posts/PagedPosts.java", """
                                public static PagedPosts of(List<Post> pageItems, int page, int size, long totalElements) {
                                    return new PagedPosts(pageItems, page, size, totalElements,
                                            (int) Math.ceil((double) totalElements / (double) size));
                                }""")),
                section("error-responses-that-work", "Error responses that work",
                        "RFC 9457 problem documents versus this app's one envelope — and the 400/409/422/504 paths that earn their statuses.",
                        text("errors:standards", """
                                The standardized shape for HTTP errors is the PROBLEM DETAIL, RFC 9457 (obsoleting 7807): a JSON object with type (a URI identifying the failure class), title, status, detail (human text), instance (which occurrence). Spring supports it natively — ProblemDetail returned from an @ExceptionHandler renders application/problem+json — and it is a good spec: standardized field names, a registry-friendly type URI. The honest comparison against THIS app's envelope: our {error:{code,message,requestId,...details}} predates nothing, standardizes nothing, and wins anyway for one reason — it is THE ONLY shape this app emits, on every endpoint, from every failure path, in every demo. A client with one parser handles every error this guide will ever produce; its error boundary (the client's ErrorPanel) works on day one and never learns a new shape. Standards pedigree buys interoperability with tooling that speaks problem+json; consistency buys simplicity for every client YOU control. For an internal API the second is worth more, and switching later is one handler away — the envelope's keys map onto problem fields almost one to one. What is NOT negotiable, in either shape: NEVER a stack trace on the wire, always a correlation id, always the same shape for the same class of failure."""),
                        text("errors:one-envelope", """
                                The mechanism that guarantees it is the piece of architecture the middleware topics built: typed failures bubble as values, the @ControllerAdvice converts the typed exception to THE envelope, and nothing else in the app is allowed to invent an error body. One handler, every AppException, details merged under the canonical keys:"""),
                        code("errors:handler", "java",
                                "guide/presentation/GlobalExceptionHandler.java — one advice, every typed failure, one envelope (404 and 409 flow through here)",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler(AppException.class)
                                ResponseEntity<ErrorResponse> handleAppException(AppException exception, HttpServletRequest request) {
                                    Failure failure = exception.failure();
                                    int statusCode = exception.statusCode();
                                    String requestId = requestId(request);
                                    // … 5xx logs error with the stack, 4xx logs warn with the message
                                    return ResponseEntity.status(statusCode)
                                            .body(ErrorResponse.of(exception.code(), failure.message(), requestId, failure.details()));
                                }"""),
                        text("errors:400-vs-422", """
                                Within that one envelope, the posts edge makes one deliberate refinement: 400 versus 422. A 400 means THE REQUEST COULD NOT BE READ — unreadable JSON, a number where a string belongs; the global handler already maps those, unchanged. A 422 means the request read fine but was JUDGED unacceptable — a 121-character title, an unknown status spelling, an update that states no version. That is the RFC 9110 meaning of Unprocessable Content, and it is genuinely useful to clients: form UIs distinguish "fix your code, the body never parsed" (400) from "fix these fields" (422, with the per-field reasons in the envelope). The mapping decision lives at the edge — the Result javadoc's promise that "the presentation edge decides how failures map onto HTTP" — in one helper every posts handler shares:"""),
                        code("errors:unprocessable", "java",
                                "guide/presentation/demo/ApiDesignDemoController.java — the 422 decision: same envelope, semantic status",
                                "server/src/main/java/guide/presentation/demo/ApiDesignDemoController.java", """
                                private static ResponseEntity<ErrorResponse> unprocessable(Failure failure,
                                        HttpServletRequest request) {
                                    return ResponseEntity.status(422)
                                            .body(ErrorResponse.of(failure.kind().errorCode(), failure.message(),
                                                    requestId(request), failure.details()));
                                }"""),
                        text("errors:409-and-503", """
                                Two more paths complete the picture, both already met. The 409 CONFLICT envelope carries the optimistic-concurrency numbers as merged details — a client can render "someone updated it first, re-read version 5 and retry" without string-parsing a human sentence. And the 503: when a dependency is genuinely unavailable, the same envelope with code service_unavailable tells clients to back off — which is exactly what the ScenarioStore's failure injection produces on demand, on the wire:"""),
                        code("errors:conflict-wire", "http", "the two envelopes this section argued for, verbatim off the wire", null, """
                                PUT /api/demo/api/posts/{id}  (a stale version in If-Match)
                                → 409 {"error":{"code":"conflict",
                                        "message":"post \"9f1c…\" changed since you read it: expected version 3, current version 5",
                                        "requestId":"a1b2c3","expectedVersion":3,"currentVersion":5}}

                                GET /api/demo/api/posts     (after PATCH /api/demo/scenario {"injectFailure":true})
                                → 503 {"error":{"code":"service_unavailable",
                                        "message":"posts API is unavailable by scenario (injectFailure=true)",
                                        "requestId":"d4e5f6","area":"api"}}""")),
                section("versioning-and-evolution", "Versioning and evolution",
                        "Additive changes are free, route versioning when they are not, and the deprecation lifecycle that retires v1 without breaking clients.",
                        text("version:additive", """
                                        The cheapest version is the one you never ship, and the discipline that keeps it cheap is ADDITIVE-ONLY CHANGE: new optional fields (clients ignore what they do not know), new endpoints, new query parameters with documented defaults, new statuses only on paths that already failed. Removing a field, renaming one, changing its TYPE, or tightening validation are the breaks — each turns a working client into a broken one, usually at 3 a.m., in code you cannot deploy. This substrate practices the discipline visibly: PostDraft tolerates unknown JSON fields (they are dropped, not rejected), creation defaults an omitted status to draft, and v1 clients that never send a version field still GET happily. The workbench demo below edits posts over v1 and has never heard of v2 — that is the goal state for every evolution you can."""),
                        text("version:route", """
                                When a break is genuinely unavoidable, name the new contract EXPLICITLY. The substrate's specimen is route versioning: /api/demo/api/v2/posts beside v1 — v2 adds a summary field to every representation and delegates to the SAME use cases. Read the delegation twice: versioning here is a REPRESENTATION concern (v1 and v2 are two projections of one resource), not a code fork. No V2PostService, no copied controller — one mapping record and a thin route:"""),
                        code("version:v2", "java",
                                "guide/presentation/demo/ApiDesignDemoController.java — v2 in full: same use case, one added field, nothing else",
                                "server/src/main/java/guide/presentation/demo/ApiDesignDemoController.java", """
                                @GetMapping(value = "/v2/posts/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
                                ResponseEntity<?> getV2(@PathVariable String id) {
                                    requireAvailable();
                                    return switch (getPost.execute(id)) {
                                        case Success<Post> success -> ResponseEntity.ok(V2Post.from(success.value()));
                                        case Failed<Post> failed -> throw AppException.of(failed.failure());
                                    };
                                }

                                record V2Post(String id, String title, String summary, String body, List<String> tags,
                                        PostStatus status, long version, Instant createdAt, Instant updatedAt) {

                                    static V2Post from(Post post) {
                                        return new V2Post(post.id(), post.title(), summarize(post.body()), post.body(),
                                                post.tags(), post.status(), post.version(), post.createdAt(),
                                                post.updatedAt());
                                    }
                                    // … summarize(): first 140 characters, ellipsized — v2's one added field
                                }"""),
                        text("version:header-and-lifecycle", """
                                HEADER versioning is the main alternative: a version rides the Accept header (Accept: application/vnd.guide.post.v2+json) or a custom X-API-Version. Its virtue is stable URLs and content negotiation done properly; its cost is INVISIBILITY — the version does not appear in logs, browser bars, curl history, or proxies' caches by default, and debugging "which version answered?" becomes a header-inspection exercise. Route versioning's ugliness (URL sprawl, two routes to maintain) is exactly its strength: the version is the first thing everyone sees. This guide chooses routes for teachability; large public APIs choose headers for cleanliness; both beat an unspoken version baked into field semantics. Either way, the DEPRECATION LIFECYCLE is the same four steps: ANNOUNCE (Sunset and Deprecation response headers on v1 with dates, plus changelog), DUAL-RUN (both versions, fully correct, long enough to span your slowest client's deploy cadence — for internal APIs, quarters; measure with the version signal itself), MONITOR (usage of v1 per client until it reaches zero, with named holdouts chased), RETIRE (v1 answers 410 Gone with a pointer to v2 — an explicit, documented grave, never a silent 404). Skipping step three is how "temporary" v1 endpoints turn five.""")),
                section("the-whole-pipeline", "The whole pipeline",
                        "One request, end to end: URL → filters → routing → binding → validation → use case → Result → status + envelope.",
                        text("pipeline:trace", """
                                Trace one request the whole way through, because every previous topic assembled exactly this conveyor. PUT /api/demo/api/posts/9f1c5a52… with an If-Match header arrives: (1) the FILTER CHAIN runs first — RequestIdFilter stamps the correlation id that will ride the response and the logs (middleware topic); the ScenarioStore guard answers 503 immediately if failure injection is on. (2) ROUTING matches the path to the update handler among every @RequestMapping (routing topic). (3) BINDING converts the body bytes through Jackson into the UpdatePostRequest record and plucks the path variable and header into parameters (request-response topic). (4) VALIDATION judges at the edge — the version statement resolves (If-Match wins, else the body's version field), PostDraft.of turns a hostile body into per-field reasons, failures answer 422 (validation topic). (5) the USE CASE executes against the PORT — UpdatePost maps the adapter's tri-state into a Result. (6) the Result maps onto HTTP: Success → 200 + ETag + body; NotFound → the typed exception the advice renders as 404; StaleVersion → 409 with both versions. Every failure path in steps 1–6 lands in ONE envelope. That conveyor, in one wiring file — the composition root where the port meets its adapter and the ScenarioStore area is registered:"""),
                        code("pipeline:config", "java",
                                "guide/config/PostsDemoConfig.java — the composition root: one port bean, five use cases, the Phase 6 swap seam marked",
                                "server/src/main/java/guide/config/PostsDemoConfig.java", """
                                @Configuration
                                public class PostsDemoConfig {

                                    @Bean
                                    PostRepository postRepository(ScenarioStore scenarioStore) {
                                        // Marks this area in the store's per-mode registry: fake data today,
                                        // the Phase 6 flip-to-live seam tomorrow (dataModeFor("api")).
                                        scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.FAKE);
                                        return new InMemoryPostRepository();
                                    }

                                    @Bean
                                    ListPosts listPosts(PostRepository postRepository) {
                                        return new ListPosts(postRepository);
                                    }
                                    // … GetPost, CreatePost, UpdatePost, DeletePost — same shape, one port
                                }"""),
                        text("pipeline:fake-now-live-later", """
                                Note what that wiring file is NOT: it is not a database file. The port's only adapter today is an in-memory map seeded with twelve deterministic posts — fake data, served by a real architecture. When the data phase arrives, a PostgreSQL adapter implements the same five methods, Flyway replays the same seed ids, and the ONE bean method above changes: that is the port/adapter payoff this topic promised, demonstrated — the storage swap touches nothing above the port, because nothing above the port ever knew there was a map. Until then the fake is not a stub of anything; it is the real adapter of record, thread-safe and optimistic-concurrency-correct, tested as seriously as the SQL one will be."""),
                        demo("pipeline:demo", "api:posts-workbench", "The posts workbench, live",
                                "The whole matrix from a console: list with pagination, status/tag filters, and page controls; create and replace through a validated form; delete; and the optimistic-concurrency story readable — a 409 renders as 'someone updated it first' with both versions, plus a reload-and-retry path. The scenario toggle PATCHes /api/demo/scenario and every posts call meets the injected 503 envelope."),
                        text("pipeline:client-mirror", """
                                The client's half of the conflict story is worth quoting, because it is the 409 section made executable: the workbench PUTs the version it read, and when the server answers 409, the envelope's detail fields become UI state — not a parsed human sentence, the machine numbers. This is why structured error details are not decoration; they are what lets a form say something true:"""),
                        code("pipeline:client-conflict", "tsx",
                                "client/src/ui/demos/api/posts_workbench_demo.tsx — the 409 as data: both versions out of the envelope, into the UI",
                                "client/src/ui/demos/api/posts_workbench_demo.tsx", """
                                if (httpStatus === 409) {
                                  const envelope = envelopeOf(body);
                                  setConflict({
                                    postId,
                                    expectedVersion: envelope?.expectedVersion ?? version,
                                    currentVersion: envelope?.currentVersion ?? version,
                                  });
                                  return;
                                }""")),
                section("terminal-companion", "The resource from a terminal",
                        "curl is the client with no opinions: full CRUD, a real 409 on a stale version, and a 503 by scenario injection.",
                        text("term:tour", """
                                The terminal is where an API design is either true or hypothetical: curl shows you the status line, the headers, and the bytes, with nothing in between. The tour runs the full lifecycle — list, create (watch Location and ETag), read, update with the version you just saw, LOSE a race to yourself by replaying the stale version (the 409 is the topic's thesis in one response), win the retry, delete — then flips the ScenarioStore and meets the injected 503. Start the server (docker compose up, or run GuideApplication), then take the tour:"""),
                        code("term:crud", "bash", "the happy path: list → create → read what Location named", null, """
                                # The listing: defaults are page=0, size=10, sort=createdAt,desc (newest first):
                                curl -s 'localhost:8080/api/demo/api/posts?size=3&sort=title,asc' | head -c 600

                                # Create — note the 201, the Location header, and ETag: W/"1":
                                curl -i -X POST localhost:8080/api/demo/api/posts \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"title":"Fielding was right about one thing",
                                       "body":"The uniform interface pays rent every single day.",
                                       "tags":["api-design"]}'

                                # Location answered a path — GET it back (ETag is the version):
                                curl -i localhost:8080/api/demo/api/posts/<id-from-Location>"""),
                        code("term:conflict", "bash", "the optimistic-concurrency story: win, lose to your stale self, win the retry", null, """
                                # Replace using the version you read (body spelling; If-Match works too):
                                curl -i -X PUT localhost:8080/api/demo/api/posts/<id> \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"title":"Fielding was right about two things","body":"…","version":1}'
                                # → 200, ETag: W/"2"  (version grew)

                                # Replay the STALE version 1 — you are now the second writer:
                                curl -s -X PUT localhost:8080/api/demo/api/posts/<id> \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"title":"My edit from before","body":"…","version":1}'
                                # → 409 {"error":{"code":"conflict","message":"… expected version 1,
                                #        current version 2 …","expectedVersion":1,"currentVersion":2,…}}

                                # The honest retry: re-read (see version 2), then PUT with 2 → 200.
                                # Deleting: 204 first, then an honest 404:
                                curl -i -X DELETE localhost:8080/api/demo/api/posts/<id>
                                curl -i -X DELETE localhost:8080/api/demo/api/posts/<id>"""),
                        code("term:validation", "bash", "semantic validation answers 422 with per-field reasons; a bogus query value too", null, """
                                # Well-formed JSON, judged unacceptable (121-char title, bogus status):
                                curl -s -X POST localhost:8080/api/demo/api/posts \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"title":"'"$(printf 'x%.0s' {1..121})"'","body":"b","status":"archived"}'
                                # → 422 {"error":{"code":"validation_failed",
                                #        "fields":{"title":"must be at most 120 characters",
                                #                  "status":"must be \\\"draft\\\" or \\\"published\\\""},…}}

                                # The URL is contract too — a bogus sort is not a 500:
                                curl -s 'localhost:8080/api/demo/api/posts?sort=author,desc'
                                # → 422 {"error":{"fields":{"sort":"must be one of title, createdAt,
                                #        updatedAt …"},…}}"""),
                        code("term:scenario", "bash", "failure injection: one PATCH, and the resource answers 503 — then v2 for contrast", null, """
                                # Flip the ScenarioStore (the demo runtime switch, topic 05):
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"injectFailure":true}'

                                # Every posts call now answers the 503 envelope:
                                curl -i localhost:8080/api/demo/api/posts
                                # → 503 {"error":{"code":"service_unavailable",
                                #        "message":"posts API is unavailable by scenario…","area":"api",…}}

                                # Back to healthy, then the v2 sibling — same resource, +summary:
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'Content-Type: application/json' -d '{"injectFailure":false}'
                                curl -s localhost:8080/api/demo/api/v2/posts?size=1 | head -c 400
                                # → items[0].summary exists beside every v1 field""")));
    }
}
