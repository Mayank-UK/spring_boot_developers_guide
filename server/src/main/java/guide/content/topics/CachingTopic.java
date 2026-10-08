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
 * Topic 21 — caching. Group caching-messaging, order 1. The two caching
 * worlds (HTTP caching on the transport vs application caching in your
 * process), the 304/validator dance, Spring's cache abstraction with the
 * AOP warning test-proven (self-invocation), explicit cache-aside over the
 * CacheBackend port topic 20 delivered, invalidation as THE hard problem,
 * stampedes, CDN/content-hash cache-busting, and what must never be cached.
 * One live demo: caching:lab (both caching paths + the HTTP specimen).
 */
public final class CachingTopic {

    private CachingTopic() {
    }

    public static Topic topic() {
        return new Topic("caching", "Caching",
                "The two caching worlds and their different tools: HTTP caching "
                        + "(Cache-Control, ETag/Last-Modified validators, the 304 round trip) "
                        + "on the transport, application caching (cache-aside over the "
                        + "CacheBackend port topic 20 delivered, @Cacheable over a "
                        + "port-backed CacheManager) in your process — plus invalidation, the "
                        + "hard problem at the center of every cache, stampedes and how to "
                        + "survive them, content-hashed assets, and the list of things that "
                        + "must never be cached at all.",
                TopicGroupId.CACHING_MESSAGING, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("two-caching-worlds", "The two caching worlds",
                        "HTTP caching is the transport layer's cache; application caching is your process's shortcut. Different tools, different failure modes — this app has both, deliberately.",
                        text("worlds:split", """
                                CACHING is two different disciplines that share a word. HTTP CACHING is the TRANSPORT layer's cache: the browser, the CDN, every proxy on the path deciding whether it can serve a stored response without bothering your server. Its controls are response HEADERS (Cache-Control, ETag, Last-Modified), its unit is the HTTP response, and its failure mode is a stale answer living somewhere you do not control. APPLICATION CACHING is YOUR process's shortcut: a key-value store consulted before doing expensive work. Its controls are code (a wrapper, an annotation), its unit is whatever you serialize, and its failure mode is YOUR process confidently serving a lie it computed itself. The two cooperate constantly — and get confused constantly — because both answer "can I skip work for this request?\""""),
                        text("worlds:failure-modes", """
                                The failure modes do not mix, which is why the split matters. A stale HTTP cache is fixed with headers you send TODAY (a new Cache-Control, a changed validator) — you never get to delete anything from a CDN you do not own; that is what validators are FOR. A stale application cache is fixed by not having written a wrong entry (or evicting it in the same process that invalidated the source of truth) — headers cannot help, because the cache sits INSIDE the call path, ahead of the code that would have produced the fresh answer. And the internet's most common caching bug is crossing the streams: per-user HTML marked public-and-immutable by a stray header, or a per-user authorization decision cached in a shared key. This guide keeps the worlds separate on purpose: topic 03 met Cache-Control as a header; THIS topic gives HTTP caching its specimen endpoint (below), then moves the rest of the page to application caching over the CacheBackend port topic 20 delivered — an app-side cache the scenario toggle flips between an in-memory fake and the real Redis server, so every claim below is runnable."""),
                        code("worlds:view", "java",
                                "guide/presentation/demo/CachingDemoController.java — the lab's instrumented answer: one read, its verdict, its key — the observable contract both worlds of this demo share",
                                "server/src/main/java/guide/presentation/demo/CachingDemoController.java", """
                                /** One instrumented read through either caching path (the report flat, not nested). */
                                record StatsView(String topicId, String mode, TopicStatsReport result, String cache,
                                        long computeTimeMs, String key, String backendMode) {
                                }
                                """),
                        text("worlds:substrate", """
                                The substrate for the page: TopicStats, an honestly expensive read — it pages EVERY post carrying a tag and reduces it to word-count analytics, behind an injectable artificial 150 ms pause (the Delayer seam: a real cache fronts a real cost, and a test must be able to prove caching without sleeping). Two wrappers sit on that one expensive read: CachedTopicStats (explicit cache-aside over the port) and AnnotatedTopicStats (the same flow declared with @Cacheable). The demo runs either path on demand and answers with the verdict, the key, and how long the call took — the compute COUNT (not the clock) is the proof, but the milliseconds are the theater that makes the point land.""")),
                section("http-caching-cache-control-304", "HTTP caching: Cache-Control and 304",
                        "Cache-Control directives, the validators (ETag vs Last-Modified), and the If-None-Match round trip — quoted from the guide's own specimen endpoint.",
                        text("http:directives", """
                                Cache-Control is the server's instruction to every cache on the path, and four directives carry almost all the weight. max-age=N: this response may be served from a cache for N seconds without asking — the freshness window. no-cache: you may store it, but EVERY reuse must revalidate (the name lies; it means revalidate-always, not never-cache). no-store: never store it — the word for responses whose storage is itself the bug (auth tokens, one-time answers). private: only the browser may cache it — no shared cache (CDN, corporate proxy) may keep a copy; the word for per-user responses. The design question each response must answer is WHO may cache this and for how long — and the honest default for API JSON is small or zero max-age plus validators, because freshness-by-seconds and correctness race the moment a write lands inside the window."""),
                        text("http:validators", """
                                VALIDATORS are the other half: a cheap token the response carries so a later request can ASK "same as mine?" instead of assuming. ETag is the strong one — an opaque string the server promises to change whenever the representation changes; a hash of the content, or (this repo's choice) the resource's VERSION, which already increments on every edit. Last-Modified is the time-based one — precise only to the second, and ambiguous when two writes land inside one. The pair costs the server almost nothing to compute and enables the best trick in HTTP: the client re-request with If-None-Match (or If-Modified-Since), and an unchanged answer costs ONE 304 Not Modified with headers only and NO BODY — the round trip still happens, but the bytes do not. That is why 304 needs the validator dance: the cache must PROVE freshness to skip the payload, and the validator is the proof it presents."""),
                        code("http:specimen", "java",
                                "guide/presentation/demo/CachingDemoController.java — the specimen: weak ETag from the version, Last-Modified from updatedAt, a 30 s freshness window — and Spring MVC turns a matching If-None-Match into the 304 itself",
                                "server/src/main/java/guide/presentation/demo/CachingDemoController.java", """
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
                                """),
                        code("http:weak-etag", "java",
                                "the same file — the validator is arithmetic on the version: cheap to compute, changes exactly when the content does",
                                "server/src/main/java/guide/presentation/demo/CachingDemoController.java", """
                                /** The weak validator from the version: changes exactly when the content does. */
                                private static String weakEtag(long version) {
                                    return "W/\\"v" + version + "\\"";
                                }
                                """),
                        text("http:weak-vs-strong", """
                                The W/ prefix marks it WEAK: "semantically equivalent, not promised byte-identical." A strong ETag obliges the server to ship the exact same bytes for the same tag — a promise this endpoint cannot make (JSON field order, clock fields), so it does not pretend. Weak validators are what If-None-Match comparisons accept, and they are almost always enough. The 304 evaluation is SPRING MVC's, not ours: when a controller returns a ResponseEntity carrying ETag/Last-Modified, the message-converter stage checks the request's If-None-Match/If-Modified-Since and short-circuits to 304 — the same conditional machinery, but note it is request-scoped: it lives in THIS process, while a shared cache's 304s happen out there, at the CDN, where your server only sees the revalidation requests that FAIL to match. GET /api/demo/caching/http-cache/headers serves the introspection: the exact three headers, their values for a real post, and the four-step round trip spelled out."""),
                        code("http:client-roundtrip", "ts",
                                "client/src/ui/demos/caching/lab_demo.tsx — revalidation is JUST a request header: the lab's second button sends the ETag the first response handed out",
                                "client/src/ui/demos/caching/lab_demo.tsx", """
                                  const conditional: HeadersInit = useValidator && etag !== null ? { 'if-none-match': etag } : {};
                                  try {
                                    return await fetch(`${config.apiBaseUrl}/demo/caching/http-cache/${postId}`, {
                                      headers: conditional,
                                    });
                                """)),
                section("spring-cache-abstraction", "The Spring cache abstraction",
                        "@Cacheable/@CacheEvict/@CachePut, the CacheManager, key SpEL — and the annotation IS an AOP proxy: the self-invocation trap from topic 06, re-armed here with a test. The lab demo runs inline.",
                        text("cache:annotations", """
                                Spring's cache abstraction is three annotations over one interface. @Cacheable on a method: check the named cache under the computed key BEFORE the body runs; entry present → return it and SKIP the method; absent → run the body, store the result. @CacheEvict: drop the entry after the method (or before it, with beforeInvocation=true, for when the method might throw). @CachePut: run the body ALWAYS and store its result — the update-the-cache-without-reading-it tool. The CACHE MANAGER (one bean, CacheManager interface) is the plug point: it decides what "a cache" IS — Boot's fallback is an in-process ConcurrentMapCacheManager (never expires, invisible to other processes: a trap dressed as a default), production names are Caffeine (in-process, sized and expired) or Redis (shared by every replica, TTLs native). The key, when you do not pass one, is the arguments hashed; the honest form names it — key = "#topicId" is SpEL over the method's parameters, readable at a glance and stable across refactors."""),
                        code("cache:annotated", "java",
                                "guide/domain/caching/AnnotatedTopicStats.java — the same cache-aside flow, declared: the body is ONLY the compute, the choreography moved into proxy machinery you cannot see from the call site",
                                "server/src/main/java/guide/domain/caching/AnnotatedTopicStats.java", """
                                @Cacheable(cacheNames = CACHE_NAME, key = "#topicId")
                                public String reportJson(String topicId) {
                                    return codec.encode(slow.compute(topicId));
                                }
                                """),
                        demo("cache:lab-demo", "caching:lab",
                                "The caching lab — both paths over one backend, invalidation, and the HTTP specimen",
                                "Pick a topic tag and run stats through either mode (explicit = the cache-aside wrapper over the CacheBackend port; annotation = the @Cacheable proxy over the same backend). Run it twice: MISS becomes HIT, computeTimeMs collapses, and the key shows which namespace answered — stats:rest vs topicStats:rest, never colliding. Invalidate names every key it dropped. Below: GET the post to capture ETag/Last-Modified/Cache-Control, then re-GET with If-None-Match and watch the 304 come back with no body."),
                        text("cache:aop-truth", """
                                THE ANNOTATION IS AN AOP PROXY. @EnableCaching registers an interceptor and wraps every bean that carries cache annotations in a subclass proxy; a cache-annotated METHOD CALL is really: proxy → interceptor (get/put) → your body. Which re-arms topic 06's self-invocation trap with higher stakes, because @Cacheable is the aspect you are most likely to trust blindly: a call through the INJECTED bean crosses the proxy and caches; a call on THIS — this.reportJson(...) from a sibling method — is plain Java dispatch inside the proxy's skin, and the cache never hears about it. The method runs, the answer returns, nothing is stored, and the next "cached" call computes again. The trap ships here as a specimen method and a test: two calls through the proxy compute ONCE; two calls through the self-call helper compute TWICE — asserted on the compute COUNT, not on feelings."""),
                        code("cache:trap", "java",
                                "the same file — the trap as a specimen: this.reportJson is plain dispatch, the interceptor never runs, the cache is never consulted and never filled",
                                "server/src/main/java/guide/domain/caching/AnnotatedTopicStats.java", """
                                public String reportJsonBypassingProxy(String topicId) {
                                    return this.reportJson(topicId);
                                }
                                """),
                        code("cache:manager", "java",
                                "guide/config/CachingDemoConfig.java — OUR manager over the port: one declared cache, its TTL decided once at wiring — the annotation path and the explicit path share ONE backend",
                                "server/src/main/java/guide/config/CachingDemoConfig.java", """
                                @Bean
                                CacheManager cacheManager(
                                        @Qualifier("cachingCacheBackend") guide.domain.redis.CacheBackend backend,
                                        ObjectMapper mapper) {
                                    return new CacheBackendCacheManager(backend,
                                            Map.of(ANNOTATION_CACHE_NAME, STATS_TTL), STATS_TTL, mapper);
                                }
                                """),
                        text("cache:choosing", """
                                Explicit port or annotation — when each wins. The ANNOTATION wins when the unit is exactly one method's return: one line, declared intent, Spring's edge cases (sync=true for single-flight, condition/unless SpEL gates, key generators) already solved. It loses when the flow grows a brain: partial hits, multi-key reads, choosing TTL per call, batching evictions, or ANSWERING QUESTIONS about the cache itself (was that a hit? what key did it use?) — the annotation does not tell you, which is why this repo's instrumentation diffs the compute COUNT around the proxy call. The EXPLICIT port wins exactly there: the wrapper IS the flow, so the wrapper can report its verdict, name its key, vary its TTL, and be unit-tested against a fake backend with zero Spring context. The demo's mode switch makes the comparison runnable rather than rhetorical — same expensive read, same backend, two doors.""")),
                section("cache-aside-the-pattern-under-the-annotation", "Cache-aside, the pattern under the annotation",
                        "The read-through sequence in plain Java: GET, hit? answer : compute → PUT with TTL — and the decisions the annotation was making for you.",
                        text("aside:sequence", """
                                Cache-aside is the pattern both paths run — spelled out, it is four steps: (1) GET the key from the cache. (2) Hit? Decode and answer — the expensive read never runs. (3) Miss? Run the expensive read (a miss is a miss: absent, evicted, and expired are indistinguishable and equally ignorable). (4) PUT the answer WITH a TTL and return it. The name says the shape: the cache sits BESIDE the data path, not in it — the source of truth never knows a cache exists, which is why cache-aside degrades to "no cache" so gracefully (cache down → you are just slow, not down). The alternative shapes: read-through/write-through (the cache sits IN the path, misses fill synchronously, writes update cache and store together) buy consistency and cost coupling; write-behind buys write latency and risks loss. Application caches overwhelmingly start cache-aside — it is the only pattern that is a PURE OPTIMIZATION, removable without touching the write side at all."""),
                        code("aside:port-version", "java",
                                "guide/domain/caching/CachedTopicStats.java — the port version, every decision visible: the key's namespace, the hit's decode, the miss's compute → put WITH TTL → answer",
                                "server/src/main/java/guide/domain/caching/CachedTopicStats.java", """
                                /** One read-through: hit → decode and answer; miss → compute, put with TTL, answer. */
                                public StatsOutcome stats(String topicId) {
                                    requireTopicId(topicId);
                                    String key = keyFor(topicId);
                                    long startedAt = System.nanoTime();

                                    Optional<String> hit = cache.get(key);
                                    if (hit.isPresent()) {
                                        return new StatsOutcome(codec.decode(hit.get()), CacheVerdict.HIT,
                                                elapsedMs(startedAt), key);
                                    }
                                    TopicStatsReport report = slow.compute(topicId);
                                    cache.put(key, codec.encode(report), TTL);
                                    return new StatsOutcome(report, CacheVerdict.MISS, elapsedMs(startedAt), key);
                                }
                                """),
                        text("aside:decisions", """
                                Read the wrapper as a list of decisions the annotation path made FOR you, silently. The KEY: "stats:" + topicId — one namespace per cached question, so this path and the annotation path can never serve each other's entries. The TTL: 60 seconds — and here is the sentence to internalize: THE TTL IS A SAFETY NET, NOT A STRATEGY. It bounds how wrong you can be, nothing more; correctness comes from invalidation (next section), and choosing TTLs as your freshness mechanism is choosing to be wrong on a schedule. WHAT gets cached: the answer INCLUDING the empty one — negative caching, caching "no such topic" for a minute, is often the biggest win in a system (the not-found query is usually the most repeated one). And the SERIALIZATION: the port speaks String, so the wrapper encodes through the ReportCodec port — an adapter concern the CacheManager version owns instead, and the same reason the annotated method returns the encoded form: both paths land byte-identical entries in the backend."""),
                        code("aside:expense", "java",
                                "guide/domain/caching/SlowTopicStats.java — the expensive read's honest shape: one artificial pause (the Delayer seam) and the count that proves everything downstream",
                                "server/src/main/java/guide/domain/caching/SlowTopicStats.java", """
                                @Override
                                public TopicStatsReport compute(String topicId) {
                                    if (topicId == null || topicId.isBlank()) {
                                        throw new IllegalArgumentException("topicId must be a non-blank string");
                                    }
                                    delayer.pause();
                                    computes.incrementAndGet();
                                """)),
                section("invalidation-the-hard-problem", "Invalidation: the hard problem",
                        "TTL expiry vs explicit evict vs event-driven invalidation; the multi-instance problem (evict here, stale there) and the pub/sub bridge; the two-hard-things quote, earned.",
                        text("invalid:quote-earned", """
                                "There are only two hard things in computer science: cache invalidation and naming things." The joke earns its keep here, because by now you have seen the easy half of caching — cache-aside is twenty lines, the annotation is one — and invalidation is where the other 80% of the work lives. Your cache holds a DERIVED truth: posts exist in PostgreSQL, the stats answer is computed from them, and the moment a post is edited your cached stats is a claim about a world that no longer exists. THREE tools exist for closing that gap, each with a cost. TTL EXPIRY: do nothing; every entry self-destructs on schedule. Costless to build, wrong by up to the TTL, and the floor under staleness — never the strategy. EXPLICIT EVICT: the write path knows what it changed and drops the matching key — precise, immediate, and COUPLED: the writer must know every cache question its write answers. EVENT-DRIVEN: the write publishes an event; every interested cache listens and drops its own entries — decoupled, and the only shape that scales past one team, at the price of the whole topic 22 machinery."""),
                        code("invalid:act", "java",
                                "guide/domain/caching/InvalidateTopicStats.java — the deliberate act, both paths at once, answering with every key it dropped: an eviction you cannot name did not happen",
                                "server/src/main/java/guide/domain/caching/InvalidateTopicStats.java", """
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
                                """),
                        text("invalid:multi-instance", """
                                THE MULTI-INSTANCE PROBLEM is invalidation's teeth. An in-process cache (ConcurrentMapCacheManager, a plain field) evicts on node A and node B keeps serving its own stale copy — forever, if no TTL. The fix is structural: EITHER every replica shares ONE cache (the Redis shape: eviction is global because there is only one entry), OR evictions must BROADCAST — which is exactly what Redis pub/sub gives you (topic 20 built the subscription machinery): the writer publishes "topic X changed" and every instance's local cache drops its copy. Shared-store invalidation is simpler and pays a network hop per read; broadcast keeps reads local and pays with a message layer plus the races pub/sub brings (a delivery racing the next read's refill). The demo's own backend is the shared shape — scenario-flipped between an in-memory fake (ONE process, eviction trivially global) and Redis (every replica, same guarantee) — and the compose files point every server at that shared Redis:"""),
                        code("invalid:compose", "yaml",
                                ".docker/docker-compose.yml — the flagged follow-up: shared stores by service name, so the cache every replica shares is reachable in compose at all",
                                ".docker/docker-compose.yml", """
                                      # …
                                      - TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
                                      # Topic 21 (caching): the shared stores by service name — a cache every
                                      # replica shares (and a Mongo reachable in compose) start with these
                                      # two env flips; application.yml keeps localhost defaults.
                                      - SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/guide
                                      - SPRING_DATA_REDIS_HOST=redis
                                """),
                        text("invalid:hygiene", """
                                Two hygiene rules close the section. EVICT IN THE SAME PATH AS THE WRITE, not "later": the space between "committed" and "evicted" is a window where every read refills the stale answer with a fresh TTL, and "later" (a scheduled job, a hope) is how a two-minute bug becomes a two-day bug. And when in doubt, evict rather than update: @CachePut and manual PUT-on-write try to keep the cache WARM through the write, which means re-deriving the cached value inside the write path — more coupling, more races, and a bug class (a wrong entry, REFRESHED by every write) that outlives the stale-entry class it replaced. Evicting costs one cold read. Updating wrongly costs an investigation.""")),
                section("stampedes-and-dogpiles", "Stampedes and dogpiles",
                        "Thundering herd anatomy: what happens when a hot key expires; lock-and-recompute, jittered TTLs, request coalescing — and the port surface each one needs.",
                        text("stampede:anatomy", """
                                A STAMPEDE is what cache-aside does under load at the exact moment of a miss: 500 concurrent requests see the SAME empty cache, and all 500 run the expensive compute — the cache was protecting you from ONE slow query, and its expiry just scheduled 500 of them, plus 500 PUTs of the same value. The dogpile variant is the aftermath: the backend, now drowning, slows down, so requests pile up waiting, so more entries expire while they wait — the failure FEEDING itself. Every mitigation is a way to make 500 misses behave like one. LOCK-AND-RECOMPUTE: one caller takes a lock for the key, computes, puts, releases; the others wait on the lock and read the fresh entry — @Cacheable(sync=true) is exactly this (the interceptor uses the cache's value-loader path; the cost is everyone blocking on one flight). JITTERED TTLs: if a thousand keys were filled by one batch job, they all expire together — add random 0–10% jitter to every TTL and the cliff becomes a slope; free, and the first thing to reach for when your stalls arrive on a schedule. REQUEST COALESCING: at the host, collapse identical in-flight reads into one shared future — singleflight, in-process, which also fixes the non-cache case (50 concurrent IDENTICAL queries)."""),
                        code("stampede:port-surface", "java",
                                "guide/data/caching/CacheBackendCacheManager.java — the honest seam: putIfAbsent over the port is check-then-act, NOT atomic — real single-flight needs SET NX, a port surface this one deliberately lacks",
                                "server/src/main/java/guide/data/caching/CacheBackendCacheManager.java", """
                                @Override
                                public ValueWrapper putIfAbsent(Object key, Object value) {
                                    String address = address(key);
                                    if (backend.get(address).isPresent()) {
                                        return get(key);
                                    }
                                    put(key, value);
                                    return null;
                                }
                                """),
                        text("stampede:honesty", """
                                Look at that putIfAbsent and see the SHAPE of the problem rather than a bug: it is check-then-act across a network — two callers can both see absent and both put, and last-writer-wins (usually harmlessly: same value). REAL single-flight over Redis is SET key token NX EX — one atomic command that is both the check and the claim — which is why the port (get/put/evict, three methods, deliberately the narrowest honest cache surface) does not pretend to offer it: add NX and you have added distributed-locking opinions to a port whose job is to be substitutable by a ConcurrentHashMap. The demo runs unmitigated cache-aside on purpose — the stampede is visible if you hammer the endpoint — and the point of this section is the diagnosis, not a patch: know WHY your p99 spikes exactly every 60 seconds, and you will know which of the three tools (lock, jitter, coalesce) the situation is asking for.""")),
                section("cdns-and-hashed-filenames", "CDNs and hashed filenames",
                        "Immutable content-addressed assets: the Vite build hash as the proof, cache-busting done right (new name) vs ?v= (same name, re-validated).",
                        text("cdn:rule", """
                                A CDN is HTTP caching at planetary scale: edge nodes caching your responses by Cache-Control, closest to the user. The whole game with a CDN is the IMMUTABILITY RULE: serve two classes of content, and give them opposite headers. Class one: API HTML/JSON — volatile, per-user, VALIDATED (small max-age or no-cache plus ETag), because the CDN must re-ask often. Class two: static ASSETS — js, css, fonts, images — files whose CONTENT never changes after deploy. For class two you write the strongest header in HTTP, Cache-Control: public, max-age=31536000, immutable ("a year, and don't even revalidate"), and you make it TRUE by putting the content's hash IN THE FILENAME: Vite (this repo's client build) already does it — every build emits assets/index-BdCkx9Qz.js, the hash of the content, referenced by a fresh index.html each deploy. Change the code, the hash changes, the NAME changes, the CDN sees a brand-new key and fetches it — cache-busting by IDENTITY instead of by invalidation. That is the entire trick, and it is the one caching technique that needs NO invalidation story at all."""),
                        code("cdn:ship", "dockerfile",
                                ".docker/Dockerfile.client.prod — where the hashed assets ship: the Vite build's dist tree (assets/index-<hash>.* by default) lands in the nginx image, one COPY away from being served",
                                ".docker/Dockerfile.client.prod", """
                                FROM nginx:1.27-alpine
                                COPY .docker/nginx.conf /etc/nginx/conf.d/default.conf
                                COPY --from=build /app/dist /usr/share/nginx/html
                                """),
                        code("cdn:edge", "nginx",
                                ".docker/nginx.conf — the edge as this repo runs it: API paths proxied (never cached), the SPA fallback serving index.html; a production edge adds one immutable location for /assets/ beside them",
                                ".docker/nginx.conf", """
                                    # Proxy API calls to the Spring Boot server (compose service name).
                                    location /api/ {
                                        proxy_pass http://server:8080;
                                        # …
                                    }

                                    # SPA history fallback: unknown paths serve the app, which renders the 404 view.
                                    location / {
                                        try_files $uri $uri/ /index.html;
                                    }
                                """),
                        text("cdn:busting-wrong", """
                                The anti-pattern the rule retires is QUERY-STRING BUSTING — app.js?v=2026-10-06. It "works" (new URL, new cache entry) but it keeps the LIE in the filename: the CDN still holds every old version forever, some proxies refuse to cache URLs with query strings at all, and any reference that forgets the ?v= serves the stale file. The hashed filename makes forgetting impossible — the reference and the identity are the same string. Note the one file that must NOT be immutable: index.html itself. It references the hashed assets, so it changes every deploy — it gets the volatile class treatment (no-cache plus validators, short TTL), and everything it POINTS at gets the year. One HTML file, revalidated cheaply forever; megabytes of assets, fetched once per content-change per user, ever.""")),
                section("what-not-to-cache", "What not to cache",
                        "Auth decisions, per-user data under shared keys, anything you cannot reconstruct — and the PII dimension (topic 25 bridge).",
                        text("not:rules", """
                                The decision test for caching a value is three questions, and a "no" on any one disqualifies it. Can I RECONSTRUCT it? A cache may lose everything at any moment — an entry that cannot be recomputed from a source of truth is not cached, it is STORED, and Redis-as-primary-database is a different (and usually wrong) architecture. Is it SHARED-shape? Auth decisions are the canonical violation: "is this token valid / does this user own post X" cached under a shared key is a privilege-escalation vending machine — the token you just revoked is a cache hit away from a minute of continued validity; authorization is recomputed per request, always, and the SPEED answer for it is making the check cheap, not cached. Is it PER-USER data under a key that does not say so? The bug is one missing dimension: caching user 42's page under "page:9" serves it to user 43 — the key must carry every axis the answer varies by (user, locale, permissions), and a key that does not is a leak with a TTL."""),
                        code("not:negative-done-right", "java",
                                "guide/domain/caching/TopicStatsReport.java — negative caching done RIGHT: the empty answer is a derived, reconstructible FACT about shared data — cache it; contrast the auth decision, which is none of those things",
                                "server/src/main/java/guide/domain/caching/TopicStatsReport.java", """
                                /** The zero-post answer — cacheable, deliberately (negative caching). */
                                public static TopicStatsReport empty(String topicId) {
                                    return new TopicStatsReport(topicId, 0, 0, 0, 0, 0.0, "");
                                }
                                """),
                        text("not:pii", """
                                The PII dimension (topic 25's bridge): a cache is a SECOND COPY of your data, and it evades your data map. The database has the row; the cache has a serialized blob under an opaque key, with a TTL, possibly in a shared Redis, possibly LINGERING after "deletion" — which is precisely what GDPR's right-to-erasure audits go looking for. The rules that keep caches compatible with data-protection obligations: cache DERIVED, MINIMIZED forms (counts, aggregates — this topic's stats report is derived facts, not user content), never raw PII payloads; keep TTLs honest (a "deleted" user's cached profile for 60 more seconds is a bug in a regulator's eyes, not a rounding error); and design deletion to know the cache exists — evict the derived keys when the source is erased, in the same path, by the same rules as section five. The comfortable summary: cache ANSWERS, not IDENTITIES — answers about shared, reconstructible facts; identities and their decisions stay uncached on purpose.""")),
                section("terminal-companion", "Terminal companion",
                        "The curl tour: MISS→HIT with timings, the invalidation receipt, and the 304 round trip captured by hand.",
                        text("term:intro", """
                                The tour runs against a dev server (compose up, or your own run). Watch the computeTimeMs field across the two identical calls — the first pays the artificial 150 ms pause, the second does not — then flip modes, invalidate, and run the validator dance by hand. The scenario endpoints are the usual GET/PATCH /api/demo/scenario; the caching backend follows the global toggle (fake in-memory by default, Redis when live and reachable)."""),
                        code("term:miss-hit", "bash",
                                "Two identical calls: MISS pays the compute, HIT does not — the verdict and the timings on one line each",
                                null, """
                                # first read: the cache is empty, the compute runs (artificial 150 ms)
                                curl -s 'localhost:8080/api/demo/caching/stats/rest?mode=explicit'
                                # → {"topicId":"rest","mode":"explicit","cache":"MISS","computeTimeMs":152,
                                #    "key":"stats:rest","backendMode":"fake","result":{…}}

                                # second read: same answer, no compute — the whole point, on one line
                                curl -s 'localhost:8080/api/demo/caching/stats/rest?mode=explicit'
                                # → {"cache":"HIT","computeTimeMs":0,"key":"stats:rest",…}

                                # the other door: the @Cacheable path, its own key namespace
                                curl -s 'localhost:8080/api/demo/caching/stats/rest?mode=annotation'
                                # → {"cache":"MISS","key":"topicStats:rest",…}   ← MISS: keys never collide
                                curl -s 'localhost:8080/api/demo/caching/stats/rest?mode=annotation'
                                # → {"cache":"HIT","key":"topicStats:rest",…}
                                """),
                        code("term:invalidate", "bash",
                                "The invalidation receipt: both paths' keys, named — then the next read is a MISS again",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/caching/invalidate/rest
                                # → {"topicId":"rest","evictedKeys":["stats:rest","topicStats:rest"]}

                                curl -s 'localhost:8080/api/demo/caching/stats/rest?mode=explicit'
                                # → "cache":"MISS" — the eviction worked, the compute returns
                                """),
                        code("term:roundtrip", "bash",
                                "The 304 round trip by hand: capture the ETag, present it, receive nothing",
                                null, """
                                POST=9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01
                                curl -s -D /tmp/h1 -o /dev/null "localhost:8080/api/demo/caching/http-cache/$POST"
                                grep -i etag /tmp/h1
                                # → ETag: W/"v1"

                                ETAG=$(awk 'BEGIN{IGNORECASE=1}/^etag:/{sub(/\\r$/,"",$2); print $2}' /tmp/h1)
                                curl -s -D - -o /dev/null -H "If-None-Match: $ETAG" \\
                                  "localhost:8080/api/demo/caching/http-cache/$POST"
                                # → HTTP/1.1 304 — validators echoed, NO body: the bytes did not ship

                                curl -s localhost:8080/api/demo/caching/http-cache/headers
                                # the introspection: the exact headers, each one's why, the 4-step round trip
                                """),
                        text("term:outro", """
                                Everything you just did is one port and one proxy: the explicit calls, the annotation calls, and the invalidation all spoke CacheBackend (fake in-memory here, Redis when the scenario flips live — and the guide:cache: namespace from topic 20 travels with it), while the 304s were pure transport, evaluated by Spring MVC before your code ever ran. Two worlds, deliberately separated, each with its own proof.""")));
    }
}
