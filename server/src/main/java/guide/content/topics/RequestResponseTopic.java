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
 * Topic 09 — Requests and Responses in Spring MVC. Topic 03 taught the
 * protocol; this topic teaches the API surface Spring gives you over it:
 * the request object and its binding sugars, body reading through the
 * converter chain, ResponseEntity and status discipline, the app's error
 * envelope, streaming, content negotiation from the converter side, and
 * cookies plus redirects — each with the live reqres demos and quoted from
 * this repo's real files.
 */
public final class RequestResponseTopic {

    private RequestResponseTopic() {
    }

    public static Topic topic() {
        return new Topic("request-response", "Requests and Responses in Spring MVC",
                "Topic 03 taught the protocol; this is the API surface over it — the request "
                        + "object and its binding sugars, ResponseEntity as full response control, "
                        + "the error envelope, streaming, converters, and cookies — with two live "
                        + "demos over one real controller.",
                TopicGroupId.MIDDLEWARE_REQUEST, 3,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-request-object", "The request object",
                        "One wire message, two API surfaces: everything MVC bound for you, and the raw servlet object underneath — the inspector demo shows both, side by side.",
                        text("req:two-surfaces", """
                                Concept first: by the time your handler method runs, the request exists twice. Once as the RAW object — in this app a HttpServletRequest, Tomcat's parsed representation of the wire message: method, request URI, headers, parameters, cookies, locale list, an input stream, and an attribute map. And once as BOUND ARGUMENTS — the values Spring MVC already extracted and type-converted into your method's parameters. Most handlers never touch the raw object; the sugars cover the common surface. But the raw object is always one parameter away, and knowing what it still owns — the URI, the query string verbatim, the auth type, attributes middleware stamped — is the difference between understanding MVC and guessing at it.

                                The two surfaces are one-to-one in places and disjoint in others. @RequestParam covers getParameter (query string AND parsed form bodies — both merge into the same parameter map), but nothing binds requestURI or getLocales for you; @RequestHeader covers getHeader; @CookieValue covers getCookies; attributes have NO sugar at all — request attributes are in-process cargo, set by filters and interceptors, and reading them is always the raw call. This app's request-id filter (topic 08) stamps a requestId attribute before any controller runs, so a live inspector response carries it: proof that middleware ran first."""),
                        code("req:sugars", "java",
                                "guide/presentation/demo/ReqResDemoController.java — the inspector's GET: four binding sugars and the raw object, in one signature",
                                "server/src/main/java/guide/presentation/demo/ReqResDemoController.java", """
                                @GetMapping(value = "/api/demo/reqres/inspect", produces = MediaType.APPLICATION_JSON_VALUE)
                                RequestSurfaceExhibit.Report get(@RequestParam MultiValueMap<String, String> params,
                                        @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept,
                                        // … USER_AGENT and x-teaching-flag bind the same way
                                        @CookieValue(name = "reqres-visit", required = false) String visit,
                                        HttpServletRequest request) {"""),
                        text("req:sugars-explained", """
                                Read that signature as four sugar levels. @RequestParam with a MultiValueMap binds EVERY query parameter into a map (repeated names keep their list) — the per-name form, @RequestParam("channel") String channel, is the same sugar narrowed to one key, with required (default true) and defaultValue for absent-tolerant binding. @RequestHeader binds named headers with the same required/defaultValue rules. @CookieValue binds one cookie by name. And the HttpServletRequest parameter — added or removed freely, position-independent — hands you the raw surface whenever the sugars do not reach.

                                The sugars are conveniences over raw getters, not separate machinery: strip the annotations and every value is still getParameter("channel"), getHeader("Accept"), or a loop over getCookies. What the sugars add is declaration (the contract is in the signature, not the body), type conversion (a @RequestParam Integer parses; a raw String must be parsed by hand), and absence policy (required=false vs a null check). The exhibit below is the demo's pure domain half: it classifies each bound value with the annotation that carried it —"""),
                        code("req:classify", "java",
                                "guide/domain/reqres/RequestSurfaceExhibit.java — the classification: every bound value stamped with its sugar; attributes get the honest note",
                                "server/src/main/java/guide/domain/reqres/RequestSurfaceExhibit.java", """
                                private static List<BoundValue> via(List<? extends Named> values, String sugar) {
                                    return values.stream()
                                            .map(value -> new BoundValue(value.name(), value.value(), sugar))
                                            .toList();
                                }

                                // …
                                private static String noteFor(String name) {
                                    if (name.equals(REQUEST_ID_ATTRIBUTE)) {
                                        return "stamped by topic 08's request-id filter — middleware ran before any controller";
                                    }
                                    return "in-process cargo — set server-side, never traveled the wire";
                                }"""),
                        demo("req:demo", "reqres:inspector", "The request inspector, live",
                                "Build a request — query parameters, selected headers, a cookie, optionally a JSON body — and send it to the inspector. The report comes back in two halves: what MVC BOUND for you (each value stamped with the annotation that carried it: @RequestParam, @RequestHeader, @CookieValue, @RequestBody) beside the RAW surface the sugars never covered (request URI, query string, auth type, content type, locales, attributes — including the requestId attribute topic 08's filter stamped, when it ran), plus a summary of the split."),
                        text("req:what-you-see", """
                                Watch the split move as you change the request. Send query parameters and they appear ONLY on the bound side — the raw half keeps the query string verbatim (one getQueryString() call away), but never parses it by hand. POST a JSON body and a fourth sugar joins: the whole body, bound at once. And the attributes list is honest about a slice test vs a live server: in slices the request-id filter never ran, so no middleware attribute appears; live, it does.""")),
                section("reading-the-body", "Reading the body",
                        "The body is a stream you can read exactly once — @RequestBody hands it to the converter chain, and only sometimes is that what you want.",
                        text("body:once", """
                                The request body is not a field; it is a stream, and a stream you can consume exactly ONCE. Every body-reading mechanism — converter or raw — drains the same InputStream. That single fact explains the shapes you meet. @RequestBody binds the WHOLE body to one parameter: the converter chain (next section, but you need the headline) picks a reader by the request's Content-Type, so Content-Type: application/json plus a record parameter means Jackson parses the entire body into that record before your method runs. There is no per-field body binding in MVC — no "@BodyField annotation — because the body is not addressable like a query string; partial updates arrive as whole JSON documents whose FIELDS are partial, and binding is still all-or-nothing.

                                The inspector's POST variant is the canonical shape — consumes declares what the endpoint accepts, produces what it answers, and @RequestBody(required = false) tolerates an empty body (JsonNode is the honest type for whatever JSON arrived):"""),
                        code("body:post", "java",
                                "guide/presentation/demo/ReqResDemoController.java — the POST variant: @RequestBody rides the same sugar set",
                                "server/src/main/java/guide/presentation/demo/ReqResDemoController.java", """
                                @PostMapping(value = "/api/demo/reqres/inspect",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                RequestSurfaceExhibit.Report post(@RequestBody(required = false) JsonNode body,
                                        @RequestParam MultiValueMap<String, String> params,
                                        // … headers and cookie bind exactly as in the GET variant
                                        HttpServletRequest request) {
                                    return unwrap(inspectRequestSurface.execute(incoming(request.getMethod(), params,
                                            accept, userAgent, teachingFlag, visit, boundBodyOf(body), request)));"""),
                        text("body:pitfalls", """
                                The pitfalls are predictable once you know the stream is single-use. A body that never parses as JSON throws HttpMessageNotReadableException BEFORE your method runs — this app maps it to a 400 validation envelope in the global advice, not a 500, because a client that sends garbage made a client-shaped mistake. A Content-Type the endpoint does not consumes is rejected earlier still: 415, without your code being touched. Unknown JSON fields are silently ignored by default (Jackson's FAIL_ON_UNKNOWN_PROPERTIES is off in Boot) — convenient and dangerous in equal measure, because a typo'd field name "disappears" instead of failing; the validation topic shows tightening this deliberately.

                                Size is the quiet pitfall. There is NO default cap on a JSON body: the converter buffers what arrives, and your heap is the limit — a hostile client can POST gigabytes. Form parameters are capped (Tomcat's maxPostSize, 2MB by default) and multipart uploads are capped (1MB per file, 10MB per request by default in Boot), but application/json arrives uncapped unless you cap it — at the reverse proxy, or in code that checks size before buffering. The limits are configuration, not architecture; knowing which door is open is the point."""),
                        code("body:unreadable", "java",
                                "guide/presentation/GlobalExceptionHandler.java — a body that never parsed is a validation failure, not a 500",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler(HttpMessageNotReadableException.class)
                                ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
                                        HttpServletRequest request) {
                                    // … logged with the request id, like every failure
                                    return ResponseEntity.badRequest()
                                            .body(ErrorResponse.of(guide.core.errors.FailureKind.VALIDATION.errorCode(),
                                                    "request body is not readable JSON", requestId, null));
                                }"""),
                        text("body:raw-escape", """
                                The raw escape hatch is getInputStream() (bytes) or getReader() (characters) — the ServletInputStream underneath is the same stream the converters would drain, so you cannot mix: a handler that reads the stream raw has nothing left for @RequestBody on the same request. Reach for raw only when the body is not a document: file uploads (the files-media topic streams MultipartFile), webhook payloads you must verify byte-for-byte with HMAC before parsing (the webhooks topic), or custom protocols. Between the sugars and the stream, the rule of thumb: documents go through converters, streams go raw.""")),
                section("responseentity-and-status", "ResponseEntity and status",
                        "Returning a record is the happy path; ResponseEntity is full control — and the control can live in the domain, as the methods demo proves.",
                        text("resp:control", """
                                A handler that returns a record (or any object) has answered 200 with a JSON body — the right shape for most reads. ResponseEntity is the full-control answer: status, headers, and body, all yours. ResponseEntity.status(201).header("Location", uri).body(record) is the whole vocabulary, and its honest name is a tiny state machine — a BodyBuilder accumulating the response until body() or build() commits it. The methods demo uses it to answer ONE path differently per verb, and — the teaching point — the per-verb decision is a DOMAIN table, not controller branching. The exhibit decides status, headers, and body as values:"""),
                        code("resp:decision", "java",
                                "guide/domain/reqres/MethodsExhibit.java — the decision table: each verb's canonical status + headers + body, as a value",
                                "server/src/main/java/guide/domain/reqres/MethodsExhibit.java", """
                                public Decision decide(String method, long creation, long revision) {
                                    String verb = normalize(method);
                                    // … unsupported verbs are rejected before this point
                                    return switch (verb) {
                                        case "GET" -> new Decision(200,
                                                note("200 OK — success with a representation"),
                                                new OutcomeBody("GET", "safe · idempotent", Map.of(
                                                        "resource", "methods-demo",
                                                        "state", "unchanged — a read, answer with the representation")));
                                        // … POST: 201 + Location · PUT: 200 + ETag
                                        case "PATCH" -> new Decision(204,
                                                note("204 No Content — applied; deliberately nothing to say"),
                                                null);
                                        // … DELETE: 200 + a confirmation body
                                    };
                                }"""),
                        text("resp:decision-explained", """
                                The controller side is then five one-line handlers and one generic mapper — no switch on the verb lives at the edge, because the edge does not make decisions; it maps them. Failures still travel as Failed Results and become typed exceptions exactly like every other handler in this app. The mapper is worth reading closely: status from the decision, headers applied as a loop, body present-or-absent — 204 carries none, and Spring enforces the protocol's rule that a 204 response has no body:"""),
                        code("resp:controller", "java",
                                "guide/presentation/demo/ReqResDemoController.java — the whole verb matrix at the edge: ask the domain, map the decision",
                                "server/src/main/java/guide/presentation/demo/ReqResDemoController.java", """
                                private ResponseEntity<MethodsExhibit.OutcomeBody> respond(String method) {
                                    return switch (decideMethodResponse.execute(method)) {
                                        case Success<MethodsExhibit.Decision> success -> toResponse(success.value());
                                        case Failed<MethodsExhibit.Decision> failed -> throw AppException.of(failed.failure());
                                    };
                                }

                                /** Generic decision → ResponseEntity mapping: no per-verb branching lives at the edge. */
                                private static ResponseEntity<MethodsExhibit.OutcomeBody> toResponse(MethodsExhibit.Decision decision) {
                                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(decision.status());
                                    decision.headers().forEach(builder::header);
                                    return decision.body() != null ? builder.body(decision.body()) : builder.build();
                                }"""),
                        demo("resp:demo", "reqres:methods", "One path, five verbs, live",
                                "Fire each verb at /api/demo/reqres/methods and watch the full response arrive: GET answers 200 with a representation, POST answers 201 with a Location naming what it created, PUT answers 200 with an ETag versioning the replacement (fire it twice — the ETag moves), PATCH answers 204 with deliberately no body, DELETE answers 200 with a confirmation record. Every response carries an x-teaching-note header stating WHY that status — the decision table's reasoning, straight from the domain."),
                        text("resp:five-answers", """
                                Two footnotes complete the toolbox. @ResponseStatus pins a status on a handler (or, more usefully, an exception class) — it is declarative but blunt, and cannot compute (the Location of a created resource is not a constant), which is why this app reaches for ResponseEntity whenever the status depends on what happened. And records ARE bodies: Jackson serializes a record's components in declaration order, so the OutcomeBody records above reach the wire as plain JSON with no mapping layer. Return the domain's records; build envelopes only when the protocol — Location, ETag, Set-Cookie — demands headers.""")),
                section("status-codes-and-envelope", "Status codes and the error envelope",
                        "Choosing the sentence is a design act; delivering every failure in one shape is a contract. This app's mapping table lives in exactly one enum.",
                        text("code:choosing", """
                                Topic 03 read status codes as sentences; request handling is where you write them. The success family is richer than 200: 200 with a representation; 201 after a create, with Location naming the new resource (POST above); 202 Accepted when you took the work but finished it later — the background-jobs answer; 204 when success deliberately says nothing (PATCH above, and the equally canonical DELETE); 206 for partial content (range requests, files topic). The failure families split by ownership: 4xx means the client's request was wrong (400 malformed, 401 unauthenticated, 403 unauthorized, 404 absent, 409 conflicting state, 429 too many), 5xx means the server failed (500 our bug, 503 deliberately unavailable). Precision is not pedantry — clients branch on statuses, caches treat them differently, and monitoring alarms on the 5xx rate but not the 4xx.

                                One deliberate convention: validation failures answer 400, never 422. Unprocessable-entity is arguably more precise for "syntactically fine, semantically rejected", but a house convention held everywhere beats a finer split held nowhere — every layer of this app says VALIDATION and the table below says 400, end to end."""),
                        text("code:envelope", """
                                For failures, the status is only half the contract — the body carries the machine-readable half. Spring's modern answer is ProblemDetail (RFC 9457): a standard shape with type/title/status/detail/instance fields, first-class support in ResponseEntity and @ExceptionHandler. It is a good default for public APIs, and the api-design topic weighs it properly. THIS app keeps its own envelope — { error: { code, message, requestId, ...details } } — as a deliberate contract decision: the code is machine-stable for branching, the message is for humans, the requestId correlates with server logs, and the guide's client parses these exact keys. One shape, every failure, no exceptions to the rule. The status mapping itself lives in exactly one enum — the single place a failure kind becomes an HTTP sentence:"""),
                        code("code:mapping", "java",
                                "guide/core/errors/FailureKind.java — the whole mapping table: every status this app emits, all eight kinds",
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
                        code("code:envelope-record", "java",
                                "guide/core/errors/ErrorResponse.java — the envelope itself: details merge first, canonical keys win",
                                "server/src/main/java/guide/core/errors/ErrorResponse.java", """
                                public static ErrorResponse of(String code, String message, String requestId, Map<String, Object> details) {
                                    Map<String, Object> body = new LinkedHashMap<>();
                                    if (details != null) {
                                        body.putAll(details);
                                    }
                                    body.put("code", code);
                                    body.put("message", message);
                                    body.put("requestId", requestId);
                                    return new ErrorResponse(body);
                                }"""),
                        text("code:walkthrough", """
                                Walk one failure through the whole chain (the exception-handling topic replays it in depth): a use case returns Result.failed with kind CONFLICT → the controller's unwrap throws the typed ConflictException → the @ControllerAdvice reads the same enum for status 409 and code "conflict" → ErrorResponse.of builds the envelope → the client's guarded fetch raises a typed failure carrying code, status, and requestId. Not one layer invented a status or a shape; the enum and the envelope are the only two places those decisions exist.""")),
                section("streaming-responses", "Streaming responses",
                        "When the answer is computed or large, do not build it in memory — StreamingResponseBody writes chunks as they are produced, and async dispatch is the fine print.",
                        text("flow:why", """
                                The default response path buffers: the handler returns a value, a converter serializes ALL of it, and only then does the first byte hit the socket. For a JSON record that is right. For output that is computed chunk by chunk (a report, an export, a log tail) or too large to hold (a generated file), buffering costs first-byte latency AND a full heap copy. StreamingResponseBody is the escape: return a lambda that receives the response's OutputStream and writes as it produces — the socket sends what you flush, the heap never holds the whole answer. This app has a real one, curl-able in the terminal companion. The writing discipline lives in the domain:"""),
                        code("flow:exhibit", "java",
                                "guide/domain/reqres/ChunkedLinesExhibit.java — the flush discipline: hand each chunk to the socket as produced",
                                "server/src/main/java/guide/domain/reqres/ChunkedLinesExhibit.java", """
                                public static void writeLines(OutputStream output, int lines) throws IOException {
                                    if (lines < 1 || lines > MAX_LINES) {
                                        throw new IllegalArgumentException("lines must be between 1 and " + MAX_LINES);
                                    }
                                    for (int line = 1; line <= lines; line++) {
                                        output.write(("chunk " + line + " of " + lines + "\\n").getBytes(StandardCharsets.UTF_8));
                                        output.flush();
                                    }
                                }"""),
                        code("flow:endpoint", "java",
                                "guide/presentation/demo/ReqResDemoController.java — a real StreamingResponseBody endpoint, with an explicit charset (the negotiation section returns to why)",
                                "server/src/main/java/guide/presentation/demo/ReqResDemoController.java", """
                                @GetMapping(value = "/api/demo/reqres/stream", produces = "text/plain;charset=UTF-8")
                                StreamingResponseBody stream(@RequestParam(defaultValue = "5") int lines) {
                                    if (lines < 1 || lines > ChunkedLinesExhibit.MAX_LINES) {
                                        throw AppException.of(Failure.of(FailureKind.VALIDATION,
                                                "lines must be between 1 and " + ChunkedLinesExhibit.MAX_LINES,
                                                Map.of("lines", lines)));
                                    }
                                    return output -> ChunkedLinesExhibit.writeLines(output, lines);
                                }"""),
                        text("flow:async", """
                                The fine print is async. A StreamingResponseBody handler returns immediately; Spring starts async processing and runs your lambda on a task executor thread while the request thread goes back to the pool. The servlet container then REDISPATCHES the request through the filter chain on completion — the same filters see it twice, distinguished by request.getDispatcherType(): REQUEST for the original pass, ASYNC for the redispatch. OncePerRequestFilter (topic 08) guarantees once per PASS, and shouldNotFilterAsyncDispatch() lets a filter skip the second one entirely — the request-id filter leaves it on, so the id stamps both passes.

                                Flush discipline is a promise, not a formality: without flush(), the container may buffer your writes into fewer, larger chunks and the client sees nothing until the end — defeated streaming. With it, each chunk flies when computed, framed by chunked transfer encoding (no Content-Length; the server closes the frame stream). The same discipline powers SseEmitter — server-sent events, where each event is a flushed block over one long-lived response. The realtime topic builds that machinery end to end (tickers, reconnects, rooms); what it sits on is exactly this: write, flush, repeat, complete.""")),
                section("content-negotiation-converters", "Content negotiation and the converter chain",
                        "Accept and produces are the contract; the HttpMessageConverter chain is the machinery that honors it — and chain ORDER decides who writes your body.",
                        text("conv:recap", """
                                Topic 03 taught negotiation as a protocol conversation: the client's Accept ranks what it can consume, the server's produces declares what a handler answers, no match means 406 (or 415, when the mismatch is on the request body's Content-Type versus consumes). This topic opens the black box: WHO actually honors those headers. The answer is a chain of HttpMessageConverters, consulted in order at both ends of the body — reading @RequestBody (canRead: does any converter read this Java type from this media type?) and writing the return value (canWrite). The first converter that raises its hand wins, which makes ORDER the load-bearing detail."""),
                        text("conv:chain", """
                                Boot's default chain, compressed to the parts you will actually hit: a byte-array converter first (byte[] bodies pass through verbatim), then a String converter (String handlers — note it writes text/plain unless produces says otherwise, and writes your string EXACTLY as given: return hand-built JSON strings and you own the quoting bugs), then resource converters, then the JSON converter for everything else — in this app Jackson 3, which is why a record return becomes a JSON body with Content-Type: application/json and no configuration anywhere. Records, maps, lists, JsonNode: all find the JSON converter. That silent default is worth one deliberate look a year, because it is the most-used code path in most APIs and almost nobody reads it.

                                Every mapping in this app declares its side of the contract explicitly — the inspector's POST is the canonical example, and its GET sibling declares produces alone:"""),
                        code("conv:produces", "java",
                                "guide/presentation/demo/HttpDemoController.java — the app's load-bearing produces/consumes pair, verbatim",
                                "server/src/main/java/guide/presentation/demo/HttpDemoController.java", """
                                @PostMapping(value = "/api/demo/http/inspect",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                HeadersExhibit.InspectReport post(@RequestBody(required = false) JsonNode body,
                                        HttpServletRequest request) {"""),
                        text("conv:custom", """
                                Custom converters are the same contract with your type in it: implement HttpMessageConverter (or extend AbstractHttpMessageConverter for the common one-type-one-media-type case) and register it through WebMvcConfigurer — configureMessageConverters REPLACES the default chain (a sharp tool: you just deleted Jackson), extendMessageConverters appends to it (almost always what you want). Before writing one, check the cheaper levers: a produces/consumes pair, a custom serializer registered with the JSON converter, or plain DTO records.

                                Charset is the negotiation detail this topic owes you: JSON is UTF-8 by specification — the bytes on the wire are UTF-8 and the header says application/json with no charset needed. text/* is NOT so lucky: text/plain has no default charset in HTTP, so a text endpoint that writes non-ASCII without declaring one is a mojibake bug waiting for its first international user. That is exactly why the streaming endpoint above declares produces with an explicit UTF-8 charset — a media type is a type plus parameters (topic 03), and for text the charset parameter is load-bearing.""")),
                section("cookies-and-redirects", "Cookies and redirects",
                        "Two small response powers: state the client will replay (cookies, with their full attribute model), and pointing a client somewhere else (forward, 302, 308).",
                        text("ck:set", """
                                Setting a cookie is writing a response header — Set-Cookie — but the header's value is a small language of attributes, and the servlet API's response.addCookie hides them behind setters that are easy to leave half-configured. Spring's ResponseCookie is the builder done right: every attribute explicit in the chain, and toString() produces the exact header value. This app has a real one — the cookie-tour endpoint sets the very cookie the inspector binds back on its next request, closing the round trip you can trace in the terminal companion:"""),
                        code("ck:tour", "java",
                                "guide/presentation/demo/ReqResDemoController.java — the cookie tour: ResponseCookie with every attribute explicit, then a 302",
                                "server/src/main/java/guide/presentation/demo/ReqResDemoController.java", """
                                @GetMapping("/api/demo/reqres/cookie")
                                ResponseEntity<Void> cookieTour() {
                                    ResponseCookie cookie = ResponseCookie.from("reqres-visit", "set-by-tour")
                                            .httpOnly(true)
                                            .sameSite("Lax")
                                            .path("/api/demo/reqres")
                                            .maxAge(Duration.ofHours(1))
                                            .build();
                                    return ResponseEntity.status(HttpStatus.FOUND)
                                            .header(HttpHeaders.SET_COOKIE, cookie.toString())
                                            .location(URI.create("/api/demo/reqres/inspect?channel=cookie-tour"))
                                            .build();
                                }"""),
                        text("ck:bind", """
                                Reading is the mirror sugar: @CookieValue binds one cookie by name with the same required/defaultValue rules every binding annotation shares — the inspector's signature (first section) binds reqres-visit exactly that way, which is how the tour closes. The attributes are the security model (topic 03 built the vocabulary; the authentication topic builds on it): httpOnly keeps the cookie out of document.cookie, sameSite=Lax withholds it from cross-site rides, path scopes where it replays, maxAge decides persistent versus session. Note what the demo deliberately omits: secure, because localhost is not HTTPS — a flag you flip the day a real domain arrives."""),
                        text("ck:redirects", """
                                Redirects are three different moves wearing similar clothes. FORWARD is not a redirect at all: the container hands the SAME request to another handler internally — one wire request, one response, the browser's address bar never learns; use it to compose handlers, not to send clients anywhere. sendRedirect — and ResponseEntity with a Location header, as in the tour above — answers 302 Found: the client must re-ask at the new URL, and BY CONVENTION the re-ask is a GET (the historical POST→GET dance: browsers honored 302 after a form submit by fetching the address). That method-rewriting is exactly wrong when the redirected request must keep its method and body: 307 Temporary Redirect and 308 Permanent Redirect preserve both — 308 is "this resource lives THERE now, permanently, exactly as asked". 303 See Other is the explicit "answer with a GET elsewhere".

                                The tour's own exchange on the wire — a 302, a Set-Cookie, then the cookie replayed by the client — is the whole model in four lines:"""),
                        code("ck:wire", "http", "the cookie tour on the wire: set + redirect, then the replay", null, """
                                GET /api/demo/reqres/cookie HTTP/1.1

                                HTTP/1.1 302
                                Set-Cookie: reqres-visit=set-by-tour; Path=/api/demo/reqres; Max-Age=3600; HttpOnly; SameSite=Lax
                                Location: /api/demo/reqres/inspect?channel=cookie-tour

                                (the client follows — and replays the cookie automatically)

                                GET /api/demo/reqres/inspect?channel=cookie-tour HTTP/1.1
                                Cookie: reqres-visit=set-by-tour""")),
                section("terminal-companion", "The terminal companion",
                        "Every topic ends at a terminal: five verbs, two surfaces, a cookie round trip, and a stream — all through curl -i.",
                        text("term:tour", """
                                curl shows you what the browser hides: the status line, every header, and the exact bytes of the body — use -i (include headers) everywhere below. The tour hits every live piece of this topic: the methods matrix first (same path, five verbs, five different answers — watch Location arrive with the 201 and the ETag move between two PUTs), then the inspector (bound sugars beside the raw surface — note the requestId attribute the filter stamped), then the cookie round trip (a 302 whose Set-Cookie you replay by hand), and finally the stream, where -N disables buffering so you watch chunks arrive as they are computed. Two honest failures close it: a rejected query parameter and a wrong content type."""),
                        code("term:curl", "bash", "the curl tour of topic 09", null, """
                                # One path, five verbs — five statuses, Location on POST, ETag on PUT:
                                curl -i localhost:8080/api/demo/reqres/methods
                                curl -i -X POST localhost:8080/api/demo/reqres/methods
                                curl -i -X PUT localhost:8080/api/demo/reqres/methods
                                curl -i -X PATCH localhost:8080/api/demo/reqres/methods
                                curl -i -X DELETE localhost:8080/api/demo/reqres/methods

                                # The inspector: bound sugars beside the raw surface (attributes included):
                                curl -s 'localhost:8080/api/demo/reqres/inspect?channel=terminal&verbose=yes' \\
                                  -H 'x-teaching-flag: from-curl' -H 'Accept: application/json'

                                # The cookie tour: 302 + Set-Cookie, then replay the cookie by hand:
                                curl -i localhost:8080/api/demo/reqres/cookie
                                curl -s localhost:8080/api/demo/reqres/inspect -H 'Cookie: reqres-visit=set-by-tour'

                                # Streaming: -N turns off curl's buffering — watch each chunk land:
                                curl -N -i 'localhost:8080/api/demo/reqres/stream?lines=5'

                                # Two honest failures — the 400 envelope, and a 415 from a wrong media type:
                                curl -i 'localhost:8080/api/demo/reqres/stream?lines=0'
                                curl -i -X POST localhost:8080/api/demo/reqres/inspect -H 'Content-Type: text/plain' -d hi""")));
    }
}
