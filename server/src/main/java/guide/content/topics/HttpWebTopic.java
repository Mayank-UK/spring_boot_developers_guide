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
 * Topic 03 — HTTP and Web Fundamentals. Framework-agnostic on purpose: the
 * protocol underneath every Spring Boot app, taught from the wire inward —
 * request journey, methods/status/headers (with the live inspector demo),
 * idempotency and safety, content negotiation, cookies and same-origin,
 * CORS, TLS, and the HTTP versions. Java/YAML/TS blocks quote this repo's
 * real files; http/bash blocks are wire and terminal transcripts.
 */
public final class HttpWebTopic {

    private HttpWebTopic() {
    }

    public static Topic topic() {
        return new Topic("http-web", "HTTP and Web Fundamentals",
                "The protocol every Spring Boot app speaks: requests, methods, status codes, "
                        + "headers, cookies, CORS, TLS, and the versions underneath — the wire "
                        + "Spring MVC interprets on your behalf.",
                TopicGroupId.FUNDAMENTALS, 3,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-request-journey", "The request journey",
                        "One click, six hops: URL to DNS to TCP to the server's accept loop to filters to your controller — and back.",
                        text("journey:url-dns-tcp", """
                                Concept first: every web interaction starts as a URL, and a URL is an address with a job list. scheme://host:port/path?query — the scheme picks the protocol and its default port (https means 443, http means 80); the host is a name a resolver must turn into an IP address; the path and query are cargo only the server ever reads.

                                Before one byte of HTTP exists, the client has to physically find the server. DNS turns a hostname into an address — cached at every layer (browser, OS, resolver) because a cold lookup costs real milliseconds. Then TCP opens the connection with its three-way handshake: SYN, SYN-ACK, ACK — one full round trip before either side can send anything. On HTTPS a TLS handshake follows (its own section below) before the first request byte may fly. A cold first request therefore costs several round trips; keep-alive reuses the connection, and HTTP/2 and HTTP/3 (below) were invented to squeeze exactly this budget."""),
                        text("journey:server-side", """
                                On the server the story starts at a socket. The operating system queues completed connections; an accept loop — inside this app's embedded Tomcat — takes them one at a time and hands each to a worker thread, which owns that request until the response's last byte is written.

                                Spring does not own the socket; it owns everything after the socket. The first code of THIS app a request meets is a servlet filter: RequestIdFilter stamps a correlation id from the incoming x-request-id header (or generates a safe one when it is missing or malformed), echoes it onto the response, and clears its logging state in a finally block. The requestId in every error envelope later in this topic was born here — before any controller ran. Filters are the topic-08 story; today just note where they sit: first."""),
                        code("journey:filter", "java",
                                "guide/core/logging/RequestIdFilter.java — the first app code a request meets: read a header, echo a header",
                                "server/src/main/java/guide/core/logging/RequestIdFilter.java", """
                                String requestId = request.getHeader(HEADER);
                                if (requestId == null || !SAFE_ID.matcher(requestId).matches()) {
                                    requestId = UUID.randomUUID().toString();
                                }
                                request.setAttribute(REQUEST_ATTRIBUTE, requestId);
                                response.setHeader(HEADER, requestId);"""),
                        text("journey:message", """
                                Then the request itself: three parts, in a fixed order. A request line — method, path with query, version: GET /api/content/http-web HTTP/1.1. Then headers — one name: value pair per line, ending at the first blank line — carrying everything from routing (Host) to body shape (Content-Type, Content-Length) to credentials (Authorization). Then, optionally, a body; the server knows where it ends because Content-Length says so, or because chunked transfer encoding frames each piece.

                                The response mirrors the shape exactly: a status line, headers, then a body. That symmetry is the entire protocol — everything a "web framework" does is interpretation layered over these two messages. The transcript below is a real one from this app — your browser sent its sibling moments ago. The handler that answers it is one mapping in a controller: the request line's method and path, matched against annotations:"""),
                        code("journey:wire", "http", "the wire exchange this page cost — request in, response out", null, """
                                GET /api/content/http-web HTTP/1.1
                                Host: localhost:8080
                                Accept: application/json
                                x-request-id: demo-42

                                HTTP/1.1 200
                                Content-Type: application/json
                                x-request-id: demo-42

                                {"version":1,"id":"http-web","title":"HTTP and Web Fundamentals"}"""),
                        code("journey:controller", "java",
                                "guide/presentation/ContentController.java — the request line, matched: method + path in, typed use case call",
                                "server/src/main/java/guide/presentation/ContentController.java", """
                                /** GET /api/content/{topicId} → 200 { version, ...topic } or 404 envelope */
                                @GetMapping("/api/content/{topicId}")
                                TopicDetail detail(@PathVariable String topicId) {
                                    return unwrap(getTopicById.execute(topicId));
                                }"""),
                        text("journey:where-spring-sits", """
                                Where exactly does Spring sit in the chain? Tomcat — embedded, its accept socket configured by server.port in application.yml — terminates TCP, parses HTTP into request and response objects, and runs the servlet filter chain. Then the DispatcherServlet, Spring MVC's front door, selects a handler for the request's method and path, binds and validates input, calls your method, serializes the return value to JSON, and maps any typed exception through the @ControllerAdvice into the error envelope. Filters run before the dispatcher and can veto (auth, CORS, rate limiting — later topics); advice runs on the way out. Topics 04 and 08 dissect those stages; from here the chain is: TCP, TLS, filters, dispatcher, controller, serializer, response."""),
                        code("journey:config", "yaml",
                                "server/src/main/resources/application.yml — the accept socket's config, verbatim (management endpoints elided)",
                                "server/src/main/resources/application.yml", """
                                server:
                                  port: 8080

                                management:
                                  endpoints:
                                    web:
                                      exposure:
                                        # prometheus joined in topic 33 (observability)
                                        include: health,info,prometheus""")),
                section("methods-status-headers", "Methods, status codes, and headers",
                        "Methods are verbs with contracts, statuses are sentences with meanings, headers are the metadata envelope — the live inspector demonstrates all three.",
                        text("msh:methods", """
                                Methods are not verbs by convention; they are contracts. GET means fetch a representation and, by contract, change nothing on the server — which is why browsers may prefetch GETs, caches may answer them, and proxies may repeat them (the next section gives that property its name: safe). POST means hand this body to this resource for processing — create something, trigger something; it is the method of consequences. PUT means replace the resource at this URI with exactly this body. PATCH means apply a partial change this body describes. DELETE means remove it. HEAD is GET without the response body — existence and metadata for the price of headers. OPTIONS asks what the resource supports — the method every CORS preflight rides on.

                                The danger zone is creative method use. A GET that mutates state breaks every cache and crawler that touches it; a POST used for a pure read costs you idempotency, retries, and caching while buying nothing. When you design endpoints (the api-design topic), the contract is load-bearing: pick the method whose promise you can keep."""),
                        code("msh:methods-wire", "http", "same endpoint, five verbs — the server's answers differ in more than the status", null, """
                                GET    /api/demo/http/inspect        → 200  echo of the request
                                HEAD   /api/demo/http/inspect        → 200  same headers, no body
                                OPTIONS /api/demo/http/inspect       → 200  Allow: GET,POST
                                POST   /api/demo/http/inspect        → 200  echo + body report
                                POST   /api/demo/http/inspect        (no JSON content-type) → 415"""),
                        text("msh:status", """
                                Status codes are sentences, and the first digit is the grammar. 1xx: informational — rare outside uploads and protocol upgrades. 2xx: success. 3xx: redirection — 301/302 for moved resources, 304 Not Modified, the caching topic's favorite reply. 4xx: the client's fault. 5xx: the server's fault. Within the families: 200 with a body; 201 Created after a successful create (with a Location header naming the new resource); 204 No Content when there is deliberately nothing to say (the classic DELETE reply); 400 malformed request; 401 not authenticated; 403 authenticated but not allowed; 404 no such resource; 409 conflicts with current state; 422 syntactically valid, semantically rejected (this guide deliberately keeps validation at 400 instead); 429 rate limited; 500 our bug; 503 deliberately unavailable.

                                What does THIS app actually emit today? Grep is the truth: 200 on every success path, plus the eight failure statuses of its taxonomy — 400, 401, 403, 404, 409, 429, 500, 503. The 201s and 204s arrive with the api-design topic's create and delete endpoints; 422 never arrives, by the convention just stated. The mapping lives in exactly one place — an enum — and one advice turns it into responses:"""),
                        code("msh:taxonomy", "java",
                                "guide/core/errors/FailureKind.java — every status this app can emit, in one enum (all eight constants, verbatim)",
                                "server/src/main/java/guide/core/errors/FailureKind.java", """
                                public enum FailureKind {
                                    VALIDATION(400, "validation_failed"),
                                    UNAUTHORIZED(401, "unauthorized"),
                                    FORBIDDEN(403, "forbidden"),
                                    NOT_FOUND(404, "not_found"),
                                    CONFLICT(409, "conflict"),
                                    RATE_LIMITED(429, "rate_limited"),
                                    UNAVAILABLE(503, "service_unavailable"),
                                    INTERNAL(500, "internal_error");"""),
                        code("msh:advice", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the single place a failure kind becomes a status code and an envelope",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                if (statusCode >= 500) {
                                    log.error("request failed: kind={} status={} requestId={}",
                                            failure.kind(), statusCode, requestId, exception);
                                } else {
                                    log.warn("request failed: kind={} status={} requestId={} message={}",
                                            failure.kind(), statusCode, requestId, failure.message());
                                }
                                return ResponseEntity.status(statusCode)
                                        .body(ErrorResponse.of(exception.code(), failure.message(), requestId, failure.details()));"""),
                        text("msh:status-200", """
                                On the success side, the app's simplest endpoint is its liveness probe — one mapping, one record, 200 every time. Jackson serializes the record's components in declaration order, and Content-Type: application/json is set by the message converters because the handler returns an object (the negotiation section below explains who decided JSON). The whole journey you just read, compressed into one small 200."""),
                        code("msh:health", "java",
                                "guide/presentation/HealthController.java — the simplest 200 this app emits, verbatim",
                                "server/src/main/java/guide/presentation/HealthController.java", """
                                @GetMapping("/api/health")
                                HealthResponse health() {
                                    return new HealthResponse("ok", "springboot-developers-guide");
                                }"""),
                        text("msh:headers", """
                                Headers are the request's metadata envelope — everything about the message that is not the message. Read them in families, because that is how the protocol uses them. Content headers (Content-Type, Content-Length, Content-Encoding) describe the body's shape. Negotiation headers (Accept, Accept-Encoding, Accept-Language) describe what the sender can consume — the next section's subject. Caching headers (Cache-Control, ETag, If-None-Match) broker reuse. Auth headers (Authorization, WWW-Authenticate) carry credentials. Session headers (Cookie, Set-Cookie) carry state. CORS headers (Origin, Access-Control-*) carry the browser's origin policy. Everything else is custom cargo (x-request-id above is this app's own).

                                The demo below classifies exactly this way, and the classifier is a pure table in this repo's domain — no Spring, no I/O, just names to categories. Two rules travel with it. Unknown headers (send an x-teaching-flag and watch) honestly report as category other rather than being guessed into a family. And Authorization and Cookie come back flagged sensitive: the inspector echoes what you deliberately sent to it, but the flag travels with the value — and no layer in the pipeline logs header values, ever."""),
                        code("msh:table", "java",
                                "guide/domain/http/HeadersExhibit.java — the classification table: standard header names to families (entries elided)",
                                "server/src/main/java/guide/domain/http/HeadersExhibit.java", """
                                private static final Map<String, Category> CATEGORIES = Map.ofEntries(
                                        Map.entry("authorization", Category.AUTH),
                                        // …
                                        Map.entry("content-type", Category.CONTENT),
                                        // …
                                        Map.entry("if-none-match", Category.CACHING),
                                        // …
                                        Map.entry("cookie", Category.SESSION),
                                        // …
                                        Map.entry("accept", Category.NEGOTIATION),
                                        // …
                                        Map.entry("origin", Category.CORS),"""),
                        code("msh:classify", "java",
                                "guide/domain/http/HeadersExhibit.java — classify one name: case-insensitive lookup, and secrets are flagged even when non-standard",
                                "server/src/main/java/guide/domain/http/HeadersExhibit.java", """
                                String lowered = name.trim().toLowerCase(Locale.ROOT);
                                Category category = CATEGORIES.get(lowered);
                                boolean known = category != null;
                                boolean sensitive = SENSITIVE.contains(lowered)
                                        || SENSITIVE_FRAGMENTS.stream().anyMatch(lowered::contains);
                                return new Classification(known ? category : Category.OTHER, known, sensitive);"""),
                        text("msh:demo-intro", """
                                The inspector is two mappings over one use case: the GET variant echoes the request — method, path, query, and every header, sorted by name and annotated with the table you just read; the POST variant accepts a JSON body and additionally reports what the server parsed out of its Content-Type. Build a request with your own headers and watch it come back classified — or flagged sensitive."""),
                        code("msh:endpoint", "java",
                                "guide/presentation/demo/HttpDemoController.java — the inspector's GET variant: thin, and declaring what it produces",
                                "server/src/main/java/guide/presentation/demo/HttpDemoController.java", """
                                @GetMapping(value = "/api/demo/http/inspect", produces = MediaType.APPLICATION_JSON_VALUE)
                                HeadersExhibit.InspectReport get(HttpServletRequest request) {
                                    return unwrap(inspectHttpRequest.execute(request.getMethod(), request.getRequestURI(),
                                            request.getQueryString(), headersOf(request), IncomingBody.absent()));
                                }"""),
                        demo("msh:demo", "http:headers", "The header inspector, live",
                                "Build a request — pick the method, add header rows, optionally a JSON body — and send it to the inspector. It echoes the method, path, and query, plus every header sorted by name and classified into caching/auth/content/session/negotiation/cors (or honestly marked other), with Authorization- and Cookie-style names flagged sensitive. The POST variant also reports the parsed Content-Type of the body you sent.")),
                section("idempotency-and-safety", "Idempotency and safety",
                        "Safe never changes anything; idempotent changes it the same way every time. Two defined words that decide whether a retry is a rescue or a duplicate.",
                        text("idem:definitions", """
                                HTTP defines two words precisely, and they decide retry semantics. Safe: the method does not change server state — GET, HEAD, OPTIONS, TRACE. (Safe does not mean unlogged: a safe request may still be logged, rate-limited, or cached; it must not mutate the resource.) Idempotent: N identical requests leave the server in the same state as one — GET, HEAD, PUT, DELETE, OPTIONS. (Idempotent does not mean identical responses: DELETE answers 200 then 404, while the state — resource gone — is exactly the same.) POST is neither: two identical POSTs create two orders, send two emails, charge two cards. The clean split: safety is about whether side effects exist at all; idempotence is about whether they survive repetition."""),
                        text("idem:put-vs-post", """
                                Why is PUT idempotent and POST not? Because of what the request URI identifies. PUT's URI names the target resource itself — PUT /articles/42 with a body says "article 42 is now exactly this"; replay it ten times and article 42 is still exactly this, so the retries collapse into one outcome. POST's URI names a processor — POST /articles says "make me a new one from this"; replay it ten times and you own ten articles. The same reasoning explains the awkward cases: DELETE is idempotent despite mutating state (the second DELETE finds nothing to do), and PATCH is technically NOT guaranteed idempotent — a patch meaning "add 1 to quantity" diverges under repetition — even though most real patches are state-sets, which are idempotent by construction."""),
                        code("idem:wire", "http", "the same body, twice — PUT converges, POST duplicates", null, """
                                PUT /articles/42        {"title": "HTTP"}   → 200
                                PUT /articles/42        {"title": "HTTP"}   → 200   (one article, still)

                                POST /articles          {"title": "HTTP"}   → 201  Location: /articles/7
                                POST /articles          {"title": "HTTP"}   → 201  Location: /articles/8   (two articles)"""),
                        text("idem:retries", """
                                Why you will care: infrastructure retries constantly, usually without asking. A proxy times out and resends; a message broker redelivers; your HTTP client has automatic retries switched on; a user double-clicks Submit. If the retried request was a GET — free. A PUT — free. A POST — a duplicate charge, unless you built a defense. The two standard defenses: an idempotency key (a client-generated token the server deduplicates on — the webhooks topic builds exactly this), and set-state bodies instead of delta bodies. This app's own PATCH endpoint is the benign case: its body sets fields to values rather than applying relative deltas, so the same patch applied twice converges to the same state — read-modify-write of absolutes:"""),
                        code("idem:store", "java",
                                "guide/core/scenario/ScenarioStore.java — set-state semantics: the same patch twice lands on the same state (idempotent by construction)",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                public synchronized ScenarioState apply(ScenarioPatch patch) {
                                    Objects.requireNonNull(patch, "patch");
                                    ScenarioState current = state;
                                    ScenarioState next = new ScenarioState(
                                            patch.dataMode() != null ? patch.dataMode() : current.dataMode(),
                                            patch.injectFailure() != null ? patch.injectFailure() : current.injectFailure());
                                    state = next;
                                    return next;
                                }""")),
                section("content-negotiation", "Content negotiation",
                        "One resource, many representations — how client and server agree on a body's shape through three headers, and how Spring's produces/consumes map onto it.",
                        text("neg:concept", """
                                The same article exists as JSON, XML, and HTML; the same API answers English and German. Content negotiation is how the parties choose without meeting. The client describes what it can consume — Accept: application/json; q=1.0, text/html; q=0.8 — where q is quality, a weight between 0 and 1 (default 1.0) that ranks the client's preferences for the server to match against what it offers. The server picks its best match and states the choice in the response's Content-Type. Then Vary tells every cache which request headers participated in the choice — "cache this per Accept-Encoding" — a cache that serves gzip bytes to a client that cannot decode them IS the outage. Three headers, one conversation; the same machinery drives Accept-Language (the i18n topic) and Accept-Encoding (compression). When no match exists, the negotiation itself fails: 406 Not Acceptable (you cannot serve what the client accepts) or 415 Unsupported Media Type (you do not accept what the client sent)."""),
                        code("neg:wire", "http", "negotiation on the wire: weighted offer, matched answer, cache instruction", null, """
                                GET /api/content HTTP/1.1
                                Accept: application/json; q=1.0, application/xml; q=0.5, text/html; q=0.1
                                Accept-Encoding: gzip, br

                                HTTP/1.1 200
                                Content-Type: application/json
                                Content-Encoding: gzip
                                Vary: Accept-Encoding"""),
                        text("neg:spring", """
                                Spring MVC exposes the same mechanism as two mapping attributes. produces declares what the handler answers — a client whose Accept excludes it gets 406. consumes declares what the handler accepts — a body of any other media type gets 415. The inspector's POST variant declares both, and its GET variant declares produces (quoted in the previous section) — these mappings are this app's real, load-bearing examples of the two attributes:"""),
                        code("neg:produces-consumes", "java",
                                "guide/presentation/demo/HttpDemoController.java — produces and consumes: negotiation declared at the mapping",
                                "server/src/main/java/guide/presentation/demo/HttpDemoController.java", """
                                @PostMapping(value = "/api/demo/http/inspect",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                HeadersExhibit.InspectReport post(@RequestBody(required = false) JsonNode body,
                                        HttpServletRequest request) {"""),
                        text("neg:client", """
                                The client half is symmetrical and real: every request this guide's React client sends carries an Accept header through one shared guarded fetch — the pipeline that turns transport failures into typed exceptions (the client's own layers get their own group of topics later):"""),
                        code("neg:client-fetch", "ts",
                                "client/src/core/error-handling/guards.ts — the client's half of the negotiation: one Accept header on every fetch",
                                "client/src/core/error-handling/guards.ts", """
                                response = await fetch(`${config.apiBaseUrl}${path}`, {
                                  headers: { accept: 'application/json' },
                                });"""),
                        text("neg:parameters", """
                                One detail the inspector makes visible: a Content-Type is a media type plus parameters, separated by semicolons — application/json;charset=UTF-8 is the media type application/json with a charset parameter. Code that compares Content-Type strings for equality breaks the moment a parameter appears; code that parses the media type out first does not. The exhibit parses exactly that split, so the demo reports the effective media type whatever parameters the client padded on:"""),
                        code("neg:content-type-parse", "java",
                                "guide/domain/http/HeadersExhibit.java — media type first, parameters after the semicolon",
                                "server/src/main/java/guide/domain/http/HeadersExhibit.java", """
                                String contentType = body.contentType();
                                String mediaType = null;
                                if (contentType != null && !contentType.isBlank()) {
                                    mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                                }""")),
                section("cookies-sessions-same-origin", "Cookies, sessions, and the same-origin policy",
                        "HTTP's memory: cookies carry state across stateless requests, sessions store it server-side, and the same-origin policy polices who may read what.",
                        text("cookie:concept", """
                                HTTP is stateless: each request arrives a stranger. Cookies are the protocol's patch — a small blob the SERVER sets (the Set-Cookie response header) and the browser stores and replays on every matching request (the Cookie request header). The attributes are the entire security model, so learn them as verbs. HttpOnly: JavaScript cannot read the cookie — document.cookie is blind to it — so an injected script exfiltrates nothing it cannot see. Secure: the cookie travels only over HTTPS. SameSite: whether the cookie rides cross-site requests at all — Lax (the sane default) sends it on top-level navigations only; Strict withholds it even there; None sends it everywhere but requires Secure — that is the cross-site API case and the CSRF discussion in the security topic. Domain and Path scope which hosts and routes see it; Max-Age and Expires decide session-cookie-dies-with-browser versus persistent."""),
                        code("cookie:wire", "http", "a cookie's full life on the wire — set with every attribute, replayed automatically", null, """
                                HTTP/1.1 200
                                Set-Cookie: sid=s%3Aabc123; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=3600

                                (later, from the same browser)

                                GET /api/demo/scenario HTTP/1.1
                                Cookie: sid=s%3Aabc123"""),
                        text("cookie:sessions", """
                                On top of cookies, sessions are the classic pattern: the server issues an opaque session id in a Set-Cookie, stores the real state keyed by that id — in memory, or in Redis when replicas must share it — and looks the state up per request. It is state the server owns, which is why the scaling topic treats sessions as a statefulness problem, and why the authentication topic builds the model twice: cookie sessions for browsers, API keys and tokens for machines that never wanted cookies. This app sets no cookies yet; when the auth demos arrive they will, and this section is the vocabulary they assume.

                                The browser-side counterpart is the same-origin policy, and its definition is short: a script may only read responses that came from the scheme + host + port it was itself served from — the ORIGIN, and anything that differs makes a different origin. A page can DISPLAY an image or SUBMIT a form cross-origin, but its JavaScript cannot read the response — localhost:5176's code cannot inspect JSON from localhost:8080 unless that response carries headers explicitly permitting it. Those headers are the next section.""")),
                section("cors", "CORS",
                        "The browser's security border, negotiated in headers before any real request flies — granted by the server, enforced by the browser.",
                        text("cors:why", """
                                Same-origin policy is the browser's rule and it is absolute by default: a page's JavaScript may not read a cross-origin response. But real products are cross-origin by design — this guide's own React dev client on port 5176 calling the API on port 8080 is already two origins, because origin means scheme + host + port and one differing component makes a different origin. CORS — Cross-Origin Resource Sharing — is the negotiated exception: requested by the browser, granted by headers. For simple requests (GET, HEAD, or POST with a boring content type and no custom headers), the request simply flies with an Origin header attached, and the browser checks the response's Access-Control-Allow-Origin before letting the script read it. Anything less simple — other methods, application/json bodies, custom headers — triggers a preflight first:"""),
                        code("cors:wire", "http", "a preflight: the browser asks OPTIONS before the real POST is allowed to exist", null, """
                                OPTIONS /api/demo/http/inspect HTTP/1.1
                                Origin: http://localhost:5176
                                Access-Control-Request-Method: POST
                                Access-Control-Request-Headers: content-type

                                HTTP/1.1 200
                                Access-Control-Allow-Origin: http://localhost:5176
                                Access-Control-Allow-Methods: GET, POST
                                Access-Control-Allow-Headers: Content-Type
                                Access-Control-Max-Age: 3600"""),
                        text("cors:enforcement", """
                                Read that exchange carefully, because the asymmetry is the part everyone gets wrong. The preflight is an OPTIONS request whose Access-Control-Request-* headers describe the real request the browser WANTS to send; the server answers with Access-Control-Allow-* grants. But the server never blocks anything — it only grants or fails to grant. It is the browser that refuses: no grant, and the real request is never sent; wrong grant, and the response is withheld from the script (the console's familiar CORS error). That is why curl has no CORS problems — no browser, no policy — and why "the API's CORS is broken" is a statement about browser pages being allowed in, not about the API's own security. Access-Control-Max-Age lets the browser cache the grant and skip preflights for a while. This app's CORS configuration arrives with the security topic, where it belongs — beside the other borders.""")),
                section("tls-and-https", "TLS and HTTPS",
                        "The padlock: what the handshake proves, what the certificate chain chains, and the honest list of what TLS does not protect.",
                        text("tls:handshake", """
                                TLS turns a readable TCP stream into an encrypted, authenticated one. The handshake, sketched: the client sends ClientHello — its supported cipher suites and a random; the server answers ServerHello — the chosen suite, its certificate, its own random; the parties run a key exchange (Diffie-Hellman) and derive the same session keys without ever transmitting them; ephemeral keys make every session fresh, so a later leak of the long-term key decrypts nothing old — forward secrecy. From the first Finished message on, everything is ciphertext. TLS 1.3 does the whole dance in one round trip — zero on resumption. What you hold when it completes: confidentiality (snoopers see noise), integrity (tampering is detected), authenticity (the certificate chain proves you are talking to the real host — that is what the padlock actually certifies)."""),
                        text("tls:certs", """
                                Three mechanism names you will meet in every TLS config, at survey depth. Certificates: the server presents a certificate chaining to a certificate authority your OS or browser already trusts; deployment work is largely obtaining certificates (Let's Encrypt's ACME protocol automates it) and rotating them before they expire — the deployment topic does that end to end. SNI, Server Name Indication: many hostnames share one IP, so the client names the host it wants INSIDE the handshake, letting the server pick the right certificate — TLS before SNI simply could not do virtual hosting. ALPN, Application-Layer Protocol Negotiation: the client offers h2 or http/1.1 inside the same handshake, which is how HTTP/2 over TLS gets selected with no extra round trip — the bridge to the next section."""),
                        text("tls:not", """
                                What HTTPS does NOT protect — the honest list. It does not hide who talks to whom: the DNS lookup, the target IP, the SNI hostname, and traffic sizes and timings remain visible — precisely the metadata an ISP or censor reads (encrypted DNS and Encrypted Client Hello shave at this, incompletely). It authenticates the server, not the client — client authentication is a separate mechanism entirely (mutual TLS, API keys, tokens: the authentication topic's opening problem). It does not make the server good: XSS, injection, and every logic flaw ride happily inside the encrypted channel — TLS is a tunnel, not a filter. And it does nothing about content you trusted: a malicious script served over HTTPS is encrypted malware. One response header extends the model — HSTS tells the browser this host is HTTPS forever, killing the one plaintext redirect hop an attacker could otherwise hijack:"""),
                        code("tls:hsts", "http", "HSTS — the header that removes the last plaintext hop", null, """
                                HTTP/1.1 200
                                Strict-Transport-Security: max-age=31536000; includeSubDomains""")),
                section("http-versions", "The HTTP versions",
                        "Thirty years of transport evolution in three versions — keep-alive, multiplexing, QUIC — and what each means for an API.",
                        text("ver:evolution", """
                                HTTP/1.1 (1997) is the version in every transcript above and in curl's default. It gave us keep-alive (reuse the connection; the handshake amortizes), the mandatory Host header (many sites, one IP), chunked transfer encoding, and the flaw that motivated everything after it: responses must answer requests strictly in order on one connection, so one slow response head-of-line-blocks everything queued behind it — browsers responded by opening six connections per origin. Pipelining was the official fix; effectively nobody shipped it. HTTP/2 (2015) rebuilt the wire as binary frames: many streams multiplex over one connection without ordering dependencies between them, headers get compressed (HPACK), and the server may push (largely abandoned in practice). HTTP/3 (2022) swapped the transport itself: QUIC over UDP merges the TLS handshake with transport establishment (one round trip, zero on resumption), recovers loss per-stream so one dropped packet stalls one stream instead of the whole connection, and migrates connections across IP changes — the mobile-network upgrade case."""),
                        code("ver:wire", "http", "the same fetch, three ways — serialization, multiplexing, streams", null, """
                                # HTTP/1.1: requests queue on the connection; six connections per origin
                                GET /api/content        →  wait →  response
                                GET /api/content/java-language  →  waits behind it

                                # HTTP/2: one connection, frames carry independent streams
                                HEADERS+DATA (stream 1: /api/content)          → interleaved
                                HEADERS+DATA (stream 3: /api/content/jvm)      → interleaved

                                # HTTP/3: QUIC streams — loss on stream 1 never stalls stream 3"""),
                        text("ver:practical", """
                                Practical impact for an API, compressed to what you will actually use. On HTTP/1.1 your API works fine; the cost appears under fan-out (one page, dozens of small requests), and the mitigations are boring: keep-alive everywhere, and fewer batched endpoints. On HTTP/2 you get multiplexing nearly free behind TLS (ALPN negotiated, semantics unchanged) — fan-out stops being a connection-count game, and domain-sharding becomes an anti-pattern you can delete. HTTP/3's wins concentrate on flaky mobile networks and fresh connections; enabling it is mostly a load-balancer or CDN decision rather than an application one. The teaching point for a Spring Boot developer: your controller code is identical across all three. The versions change the transport, never the semantics of method, status, and header this topic taught — Spring and the platform beneath it absorb the difference.""")),
                section("terminal-companion", "The wire from a terminal",
                        "Every topic ends at a terminal: the inspector, the status codes, and the raw exchange, driven by curl.",
                        text("term:tour", """
                                curl is the honest client — no browser policies, no framework, just what the wire carries. The inspector answers everything this topic taught: send custom headers and watch the classification table judge them, POST a JSON body and read the Content-Type report, HEAD and OPTIONS the endpoint to see Spring's automatic answers, and read the exchange raw with -i — including the x-request-id echo the filter stamped. Then watch failure shapes: an unknown topic is the 404 envelope from the taxonomy, and a POST without its JSON content type is a 415. Start the server (docker compose up, or run GuideApplication), then take the tour:"""),
                        code("term:curl", "bash", "the curl tour of topic 03", null, """
                                # The inspector echoes what you sent — sorted, classified, flagged:
                                curl -s 'localhost:8080/api/demo/http/inspect?channel=terminal' \\
                                  -H 'x-teaching-flag: from-curl' -H 'Authorization: Bearer demo-token'

                                # Raw exchange with -i: status line, headers (x-request-id!), then body:
                                curl -i localhost:8080/api/health

                                # HEAD — GET's headers without the body:
                                curl -I localhost:8080/api/content

                                # OPTIONS — what Spring says this endpoint allows:
                                curl -i -X OPTIONS localhost:8080/api/demo/http/inspect

                                # The POST variant: a JSON body, and its parsed Content-Type in the report:
                                curl -s -X POST localhost:8080/api/demo/http/inspect \\
                                  -H 'Content-Type: application/json' -d '{"hello":"wire","count":3}'

                                # And two honest failures — the 404 envelope, and a 415 from a wrong media type:
                                curl -i localhost:8080/api/content/no-such-topic
                                curl -i -X POST localhost:8080/api/demo/http/inspect -H 'Content-Type: text/plain' -d hi""")));
    }
}
