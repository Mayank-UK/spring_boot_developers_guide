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
 * Topic 30 — Exception handling. Group platform, order 1. The whole error
 * story of this app in one place: values vs throws (and why this codebase
 * uses BOTH, on purpose), the eight-kind taxonomy behind one envelope, the
 * global @ControllerAdvice dissected branch by branch (including the
 * framework-ErrorResponse fix that turned unrouted 500s into honest 404s),
 * RFC 9457 ProblemDetail as the opt-in second representation, failure
 * injection as a feature (the errors explorer), what logs at which level,
 * and the process-level nets below the request path.
 */
public final class ExceptionHandlingTopic {

    private ExceptionHandlingTopic() {
    }

    public static Topic topic() {
        return new Topic("exception-handling", "Exception Handling",
                "Values at the seams, throws at the edges: the eight-kind "
                        + "taxonomy behind one error envelope, the global advice that renders "
                        + "it (framework 404s included), RFC 9457 as the opt-in second "
                        + "representation, failure you can inject on demand, what logs at "
                        + "which level — and the process nets under it all.",
                TopicGroupId.PLATFORM, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("errors-as-values-vs-throws", "Errors as values, errors as throws",
                        "Java's checked-exception history, the functional alternative — and why this app deliberately uses both, each on its own side of the process boundary.",
                        text("vals:checked-history", """
                                Java is the language that bet hardest on exceptions. Checked exceptions arrived in 1.0 with a promise: the compiler forces you to acknowledge failure, so failure cannot be forgotten. Thirty years delivered a more honest verdict. They are a CLASS-LEVEL coupling — every signature between thrower and handler must declare or wrap the failure, so one new failure mode in a leaf method recompiles the tree; they compose badly with lambdas and streams (nothing in java.util.function throws); and the industry's reflex, `catch (Exception e) { throw new RuntimeException(e); }`, converts the promise into noise. Modern Java quietly stopped adding them — streams, CompletableFuture, virtual threads are all checked-exception-free zones. Kotlin, Scala, and Rust took the other road: failures as ordinary VALUES in the type.

                                The value school's argument is control flow: a returned failure is DATA you can map, accumulate, or ignore only deliberately — no invisible unwinding. Its cost is discipline: Java has no must-use on a returned Result, so you can forget to CHECK it. The throw school's argument is locality: `throw` crosses any number of frames in one hop, keeping the happy path readable. Its cost is that invisibility — every frame the exception flies over is a frame that did not get a vote."""),
                        text("vals:this-app-both", """
                                This app's answer is not a side — it is a PLACEMENT rule: FAILURES ARE VALUES AT USE-CASE SEAMS, THROWS ACROSS PROCESS BOUNDARIES. Inside the domain, a use case never throws: it returns Result<T>, whose failure arm carries exactly one typed Failure; callers branch, compose, and test without catching anything. The Result is sealed — exactly two arms, compiler-checked switches. You met it in the routing topic's routed-404 (GetTopicById returning a not_found Result) and every demo since:"""),
                        code("vals:result", "java",
                                "guide/core/result/Result.java — the sealed two-arm type: use cases return failures as values",
                                "server/src/main/java/guide/core/result/Result.java", """
                                public sealed interface Result<T> permits Success, Failed {

                                    static <T> Result<T> failed(guide.core.errors.Failure failure) {
                                        return new Failed<>(failure);
                                    }
                                    // … map() transforms the success arm; failures pass through untouched
                                }"""),
                        text("vals:why-values", """
                                Why values there? Because use-case seams are POLICY territory: the caller decides what a failure means — fall back to fake data (the optional-service discipline of the mongo and redis topics), merge it into a bigger answer, or propagate it. That decision belongs to the caller as an explicit switch, not to an invisible unwind.

                                Then the edge flips the representation. A controller that receives a Failed Result converts it to the TYPED EXCEPTION matching the failure's kind and throws it, knowing ONE piece of infrastructure catches it — every controller repeats the same five-line conversion:"""),
                        code("vals:bridge", "java",
                                "guide/presentation/demo/JobsDemoController.java — the edge conversion: a Failed Result becomes the typed exception",
                                "server/src/main/java/guide/presentation/demo/JobsDemoController.java", """
                                private static <T> T unwrap(Result<T> result) {
                                    return switch (result) {
                                        case Success<T> success -> success.value();
                                        case Failed<T> failed -> throw AppException.of(failed.failure());
                                    };
                                }"""),
                        text("vals:why-throws", """
                                Why throws there? Because the HTTP edge is a PROCESS BOUNDARY: the failure must escape any number of internal frames and land in exactly one renderer, the global advice you will dissect shortly. Throwing is the JVM's native non-local exit, and the edge is the one place its invisibility is a FEATURE — no controller ever hand-builds an error response, so no two controllers can disagree about the shape. The factory that builds the typed exception is itself a total switch over the taxonomy:"""),
                        code("vals:appexception", "java",
                                "guide/core/errors/AppException.java — the bridge: one typed subclass per kind, a total switch",
                                "server/src/main/java/guide/core/errors/AppException.java", """
                                public static AppException of(Failure failure) {
                                    return switch (failure.kind()) {
                                        case VALIDATION -> new ValidationException(failure);
                                        // … one typed subclass per kind, the switch is total
                                        case INTERNAL -> new InternalException(failure);
                                    };
                                }"""),
                        text("vals:verdict", """
                                The honest comparison: values keep failure in the type system where domain logic reasons about it; throws move failure across boundaries where infrastructure renders it. Using both is not indecision — a use-case seam and a process boundary are DIFFERENT problems wearing the same word. Three payoffs: TESTABILITY (use cases are tested by asserting on returned values — no catch blocks), LOCALITY (failure arms sit exactly where policy lives), and SHAPE — because every failure becomes an AppException at the edge, ONE advice renders the entire app's failures in ONE envelope. That last payoff is the next section's subject, and the reason "one error taxonomy end-to-end" is an invariant of this repo rather than an aspiration.""")),
                section("the-taxonomy-eight-kinds-one-envelope", "The taxonomy: eight kinds, one envelope",
                        "A closed enum decides status and code for every failure the app can produce — the single place those mappings live, rendered live by the catalog.",
                        text("tax:closed-enum", """
                                Most codebases let status codes happen to them: this controller returns 400, that one 422, someone whispered 500 into a catch block three years ago. The fix is structural — a CLOSED VOCABULARY owned by one type. This app's FailureKind enum is the entire taxonomy: eight kinds, each carrying its HTTP status and its machine-readable code. No layer below the advice knows a status number; no controller invents a code string. Adding a failure mode means adding a KIND — a one-file, reviewed, compile-checked change — and every mapper, test, and client immediately understands it:"""),
                        code("tax:enum", "java",
                                "guide/core/errors/FailureKind.java — the whole taxonomy: kind → status → code, in one closed enum",
                                "server/src/main/java/guide/core/errors/FailureKind.java", """
                                public enum FailureKind {
                                    VALIDATION(400, "validation_failed"),
                                    // …
                                    RATE_LIMITED(429, "rate_limited"),
                                    UNAVAILABLE(503, "service_unavailable"),
                                    INTERNAL(500, "internal_error");

                                    private final int httpStatus;
                                    private final String errorCode;
                                }"""),
                        text("tax:families", """
                                Read the eight as three families. The CLIENT'S FAULT family (4xx): VALIDATION — input failed a boundary guard or @Valid constraint; UNAUTHORIZED — no or bad credentials (the authentication topic); FORBIDDEN — authenticated but not allowed, the authorization topic's role/ownership verdict; NOT_FOUND — a ROUTED lookup that found nothing (unknown topic id, unknown post); CONFLICT — the request lost a race: duplicate unique key, stale version; RATE_LIMITED — the caller blew through its Bucket4j quota (the security topic). The OUR-FAULT family: INTERNAL — a genuine bug, generic on the wire because internals are not the client's business. The NEITHER-OF-US family: UNAVAILABLE — an optional dependency is down; the typed 503 the mongo, redis, and messaging topics serve instead of crashing, because degrade-gracefully beats die-honestly when the dependency is optional.

                                Each failure is one value — the kind, a wire-safe message, and OPTIONAL structured details:"""),
                        code("tax:failure", "java",
                                "guide/core/errors/Failure.java — one discriminated failure value: kind, wire-safe message, optional immutable details",
                                "server/src/main/java/guide/core/errors/Failure.java", """
                                public record Failure(FailureKind kind, String message, Map<String, Object> details) {

                                    public Failure {
                                        java.util.Objects.requireNonNull(kind, "kind");
                                        // …
                                        details = details == null ? null : Map.copyOf(details);
                                    }
                                }"""),
                        text("tax:envelope-intro", """
                                The message is written for the CALLER, never leaking internals; the details are structured data — the postId that was not found, the retryAfterSeconds of the rate limit — kept null when absent so a failure describes exactly what it carries. The presentation edge merges everything into the envelope, details first, canonical keys last so they win on collision:"""),
                        code("tax:envelope", "java",
                                "guide/core/errors/ErrorResponse.java — the one shape every failure leaves in: details merge in, canonical keys win",
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
                        text("tax:envelope-shape", """
                                The envelope is deliberately boring: one wrapper object, one nested error, three canonical keys — code (machine-readable, the enum's), message (human-readable), requestId (the correlation id the request-id filter stamped, so an error pasted into a bug report finds exactly one log line) — plus the failure's details merged in BESIDE them (canonical wins on collision). Every failure the app produces — every 400, every 503, every injected demo failure below — leaves in this exact shape, which is why the guide's own client parses failures with one code path. The taxonomy is also the demo: GET /api/demo/errors/catalog serves these eight rows plus the two framework rows you will meet in the-404-problem — kind, status, code, when-it-fires, and the exact example message /inject renders for that kind. Catalog and injector share one source of descriptors, so the docs CANNOT drift from the behavior.""")),
                section("controller-advice-the-global-handler", "Controller advice: the global handler",
                        "One @RestControllerAdvice renders every failure — dissected branch by branch: selection by specificity, the validation branch, the framework branch, the catch-all, and the edge where no advice can help.",
                        text("adv:one-place", """
                                The rendering edge is ONE class, GlobalExceptionHandler — a @RestControllerAdvice, which means: for ANY controller in the app, when a handler method throws, the DispatcherServlet's exception resolvers consult this class FIRST, matching @ExceptionHandler methods by exception type. It is the express error middleware's exact Spring counterpart, and its value is the same: one place shapes every failure, so no controller hand-builds an error response and no two routes can disagree about the envelope. The workhorse branch renders AppExceptions — every typed failure the use cases produce:"""),
                        code("adv:appexception", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the workhorse: kind → status/code/message/details, warn-or-error by family",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler(AppException.class)
                                ResponseEntity<ErrorResponse> handleAppException(AppException exception, HttpServletRequest request) {
                                    Failure failure = exception.failure();
                                    int statusCode = exception.statusCode();
                                    String requestId = requestId(request);

                                    if (statusCode >= 500) {
                                        log.error("request failed: kind={} status={} requestId={}",
                                                failure.kind(), statusCode, requestId, exception);
                                    } else {
                                        log.warn("request failed: kind={} status={} requestId={} message={}",
                                                failure.kind(), statusCode, requestId, failure.message());
                                    }
                                    return ResponseEntity.status(statusCode)
                                            .body(ErrorResponse.of(exception.code(), failure.message(), requestId, failure.details()));
                                }"""),
                        text("adv:validation-branches", """
                                Everything the envelope promised is visible there: status and code come from the failure's KIND (never the controller's mood), the details ride along, the requestId comes from the attribute the filter stamped. The branch also decides LOG LEVEL by status family — 4xx warn with the message, 5xx error with the full stack — which the what-logs section grounds.

                                Two more branches predate this topic. A body that never parsed as JSON (HttpMessageNotReadableException) is a VALIDATION failure, not a 500 — the client sent bytes that are not JSON, that is a 400. And a body that parsed but failed @Valid (MethodArgumentNotValidException, from the validation topic) renders a 400 whose details carry a fields map — first message per field — while the LOG records field names only, never rejected values (one of them may be a password; the security topic's never-log-payloads rule, applied):"""),
                        code("adv:manv", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the validation branch (topic 10): field names on the log line, first-message-per-field in the envelope",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler(MethodArgumentNotValidException.class)
                                ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception,
                                        HttpServletRequest request) {
                                    // …
                                    log.warn("request failed: kind=validation status=400 requestId={} fields={}",
                                            requestId, fieldErrors.keySet());
                                    return ResponseEntity.badRequest()
                                            .body(ErrorResponse.of(FailureKind.VALIDATION.errorCode(),
                                                    "request body failed validation", requestId, Map.of("fields", fieldErrors)));
                                }"""),
                        text("adv:selection", """
                                HOW IS A BRANCH CHOSEN? Not by declaration order — by SPECIFICITY. When an exception reaches the resolver, Spring walks the handler methods and picks the one whose declared type is the closest SUPERCLASS of the thrown type (an exception-depth comparison, like the overload resolution you already know). A concrete subclass beats an interface beats Exception. This matters the moment branches overlap, and they DO overlap by design: MethodArgumentNotValidException implements Spring's ErrorResponse interface (the framework branch below), yet the validation branch above still wins for it — its declared type is exact, depth zero, while the interface family is one hop away. The rule to internalize: add specific branches freely; the resolver routes to the best one; the catch-all is the safety net for everything unmatched, never the first resort:"""),
                        code("adv:catchall", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the safety net: generic on the wire, complete in the log",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                /** The safety net: unknown faults get the same envelope, generic message, full log. */
                                @ExceptionHandler(Exception.class)
                                ResponseEntity<ErrorResponse> handleUnknown(Exception exception, HttpServletRequest request) {
                                    String requestId = requestId(request);
                                    log.error("unhandled request error: requestId={}", requestId, exception);
                                    return ResponseEntity.internalServerError()
                                            .body(ErrorResponse.of(guide.core.errors.FailureKind.INTERNAL.errorCode(),
                                                    "Internal server error", requestId, null));
                                }"""),
                        text("adv:catchall-reading", """
                                The catch-all is deliberately anonymous on the wire — "Internal server error", nothing from the real exception — because an unexpected exception's message is an internals leak (a SQL fragment, a file path) — while the LOG gets the full stack. Generic to the client, complete to us, joinable by requestId.

                                One edge no advice can fix, worth knowing before it bites: the advice runs INSIDE the DispatcherServlet, BEFORE the response is committed. A failure mid-STREAM — a StreamingResponseBody or SseEmitter that already flushed bytes (the realtime topic's ticker) — cannot render an envelope; the first bytes are gone and HTTP has no un-send. The connection breaks, the log keeps the failure with its requestId, and the client's retry logic (the reliability topic) owns the recovery.

                                The branch this topic ADDED closes a real bug (the full story is the-404-problem below): Spring itself throws typed exceptions for routing misses — and those were falling into the catch-all as 500s. The fix is a fourth branch keyed on the framework's own exception families:"""),
                        code("adv:errorresponse", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the framework branch: Spring's own misses keep their status in our envelope",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler({NoResourceFoundException.class,
                                        HttpRequestMethodNotSupportedException.class,
                                        HttpMediaTypeException.class,
                                        ServletRequestBindingException.class})
                                ResponseEntity<ErrorResponse> handleFrameworkErrorResponse(ServletException exception,
                                        HttpServletRequest request) {
                                    org.springframework.web.ErrorResponse framework =
                                            (org.springframework.web.ErrorResponse) exception;
                                    int statusCode = framework.getStatusCode().value();
                                    // … the interface supplies ProblemDetail body, headers (Allow), title
                                    return ResponseEntity.status(statusCode)
                                            .headers(framework.getHeaders())
                                            .body(ErrorResponse.of(envelopeCodeFor(statusCode), message, requestId, details));
                                }"""),
                        text("adv:framework-reading", """
                                Two load-bearing details in that signature. First: @ExceptionHandler values must be CLASSES EXTENDING THROWABLE — Spring's ErrorResponse is an interface, so it cannot be named directly; the branch lists the Throwable families the framework's misses live under (NoResourceFoundException for unrouted paths, HttpRequestMethodNotSupportedException for wrong methods, HttpMediaTypeException for wrong Content-Type or Accept, ServletRequestBindingException for missing request parameters) and treats them AS the interface — every listed type implements it. Second: the exception's problem-details BODY supplies the message (the framework wrote a good one: "No static resource api/definitely/not/routed."), and its headers ride along — the Allow header survives a 405, so clients still learn what WOULD work. The status becomes the envelope's status; the code is derived from it:"""),
                        code("adv:codefor", "java",
                                "guide/presentation/GlobalExceptionHandler.java — status → code: exact taxonomy match, else the reason phrase snake_cased",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                private static String envelopeCodeFor(int statusCode) {
                                    return Arrays.stream(FailureKind.values())
                                            .filter(kind -> kind.httpStatus() == statusCode)
                                            .findFirst()
                                            .map(FailureKind::errorCode)
                                            .orElseGet(() -> {
                                                HttpStatus status = HttpStatus.resolve(statusCode);
                                                return status != null
                                                        ? status.getReasonPhrase().toLowerCase(Locale.ROOT).replace(' ', '_')
                                                        : "http_" + statusCode;
                                            });
                                }"""),
                        text("adv:codefor-reading", """
                                404 maps to the taxonomy's not_found, 503 to service_unavailable; a status the taxonomy never emits (405) becomes the reason phrase snake_cased — method_not_allowed. The envelope stays the one contract even for framework-raised statuses, and the resolver's specificity rule holds across the whole file: an AppException of a specific subclass beats the AppException branch, the AppException branch beats Exception, and nothing in the file depends on the ORDER the methods are written.""")),
                section("problemdetail-rfc-9457", "ProblemDetail: RFC 9457, the standard shape",
                        "The IETF's answer to error envelopes — Spring supports it natively — and this app's dual-representation demo: one failure, two contracts, chosen at the edge.",
                        text("pd:the-standard", """
                                Everything above is OUR envelope — a house contract. The IETF standardized one too: RFC 9457, "Problem Details for HTTP APIs" (it obsoletes 7807), served as the application/problem+json media type. Its shape: type (a URI identifying the failure CLASS), title (a short human summary), status (repeated in the body), detail (what happened, this instance), instance (the request path), plus extension members for anything machine-readable. It is our envelope's vocabulary wearing standard clothes, and its win is INTEROP: generic tooling, gateways, and half the ecosystems you integrate with recognize the media type without being told anything about guide-specific codes.

                                Spring's support is native. ProblemDetail models the RFC; the whole ErrorResponse family from the previous section already CARRIES one; an @ExceptionHandler may return ResponseEntity&lt;ProblemDetail&gt; — Spring serializes it, and with the content type set it answers application/problem+json. So the honest question is not HOW but WHEN.

                                This app's answer: THE ENVELOPE IS THE APP-WIDE CLIENT CONTRACT; PROBLEMDETAIL IS THE INTEROP OPT-IN. The guide's own client, demos, and tests parse the envelope — switching the whole app would churn every consumer for zero new information. A route consumed by third parties — a public webhook callback, a partner feed — should speak the STANDARD shape, because those consumers did not read our docs. Same taxonomy, same statuses, same log discipline; different bytes at the edge. The failure does not choose its representation — the EDGE does, per route. The mechanism is a marker exception, and a second rendering branch in the same advice:"""),
                        code("pd:branch", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the RFC 9457 branch: same failure, same log treatment, standard shape",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler(ProblemDetailException.class)
                                ResponseEntity<ProblemDetail> handleProblemDetailRendition(ProblemDetailException exception,
                                        HttpServletRequest request) {
                                    // … same kind, same status, same warn/error log split as the envelope arm
                                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                            HttpStatusCode.valueOf(statusCode), failure.message());
                                    // … title from the status phrase, type from the envelope code
                                    problem.setProperty("code", exception.code());
                                    problem.setProperty("requestId", requestId);
                                    return ResponseEntity.status(statusCode)
                                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                                            .body(problem);
                                }"""),
                        text("pd:opt-in", """
                                The marker itself is four lines: ProblemDetailException extends AppException, carries an ordinary typed Failure, and asks only for the standard rendition. The advice branch reads the SAME failure object the envelope branch would — the SAME warn/error log split, the SAME requestId — and serializes RFC 9457 fields instead, with our code and requestId riding as extension members (type for interop; code and requestId for us and our logs). A route opts in by throwing the marker through the same use-case-to-typed-throw conversion as every other edge:"""),
                        code("pd:route", "java",
                                "guide/presentation/demo/ErrorsDemoController.java — the opt-in route: same use case, the marker exception instead of the envelope throw",
                                "server/src/main/java/guide/presentation/demo/ErrorsDemoController.java", """
                                @GetMapping(value = "/api/demo/errors/problem-detail",
                                        produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                                Void problemDetail(@RequestParam String kind,
                                        @RequestParam(defaultValue = "false") boolean includeDetails) {
                                    return switch (injectFailure.execute(parseKind(kind), includeDetails)) {
                                        case Success<Void> ignored -> null;
                                        case Failed<Void> failed -> throw new ProblemDetailException(failed.failure());
                                    };
                                }"""),
                        text("pd:dual", """
                                Fire both routes with the same kind and compare (the explorer below does this side by side): 409 twice, conflict twice, one message twice — once as {error:{code,message,requestId,slug}} and once as {type,title,status,detail,code,requestId,slug}. TWO REPRESENTATIONS, ONE TRUTH: both branches read the same Failure, so they cannot disagree about status or meaning; only the serialization is a choice. Keep the taxonomy singular, make rendering plural and explicit, and let each route declare which contract its consumers speak.""")),
                section("the-404-problem", "The 404 problem: a fixed bug, told properly",
                        "Symptom, cause, fix, and the truth-updates that followed: unrouted paths rendered 500 for half the curriculum because the catch-all was the only net.",
                        text("four:symptom", """
                                SYMPTOM. For most of this guide's life, GET /api/definitely/not/routed returned a 500 internal_error envelope. Not a 404: five hundred, "our fault", full stack in the log. Two tests pinned the wrong behavior with honest comments: the graphql topic's default-profile suite (/graphiql outside dev) expected the 500, and the middleware topic's ring recorded the unrouted path leaving with status 500. A wrong method on a routed path did the same — 500, not 405. Meanwhile a ROUTED miss (GET /api/content/no-such-topic) rendered a perfect typed 404. Two kinds of miss, two different answers, one a lie about whose fault it was.

                                CAUSE. The routing topic established the mechanics with live probes: in a default Boot app an unrouted path does NOT produce "no handler" — Boot auto-registers a static-resource handler on /**, finds no file, and throws NoResourceFoundException; a wrong method resolves to HttpRequestMethodNotSupportedException. Both implement Spring's ErrorResponse interface — the framework's typed, status-carrying exception family. And both are, mechanically, EXCEPTIONS — so they sailed past every specific branch of the advice and landed in the catch-all, whose job is to say "unknown fault → 500". Nothing was misconfigured; the net was simply too blunt for exceptions that arrive PRE-TYPED with their own status.

                                FIX. The framework branch you dissected in the controller-advice section. Unrouted → 404 not_found with "No static resource api/definitely/not/routed." as the message; wrong method → 405 method_not_allowed, Allow header intact. The graphql test flipped from pinning the bug to pinning the fix:"""),
                        code("four:test", "java",
                                "server/src/test/java/guide/presentation/graphql/PostsGraphqlControllerTest.java — the flipped pin: outside dev, /graphiql answers the typed 404 envelope",
                                "server/src/test/java/guide/presentation/graphql/PostsGraphqlControllerTest.java", """
                                mockMvc.perform(get("/graphiql"))
                                        .andExpect(status().isNotFound())
                                        .andExpect(jsonPath("$.error.code").value("not_found"))
                                        .andExpect(jsonPath("$.error.requestId").isNotEmpty());"""),
                        text("four:collateral", """
                                TRUTH-UPDATE COLLATERAL — the part most teams forget. Two topics had taught the 500 as current behavior, in prose and in curl tours; a fixed codebase with stale docs is lying with a straight face. The routing topic's matching-and-404 section now tells it as HISTORY ("a bug this topic flagged and the exception-handling topic fixed"), its wire transcript and terminal tour show the 404. The graphql topic's two graphiql notes now state the fixed truth instead of promising it. The lesson generalizes: when you fix behavior other code documents, grep the docs too — the fix is not done until the prose stops describing the bug. One Boot 4 wrinkle worth keeping: in @WebMvcTest slices the SAME exception appears (the slice registers the resource handler too), so the fix is testable per-slice — the test above runs in one, without the full app.

                                Why /graphiql at all? The cleanest unrouted path this app owns DELIBERATELY: GraphiQL mounts only inside a dev-profile yaml document, so outside dev the route genuinely does not exist — the document below is why the test's GET meets NoResourceFoundException rather than a redirect. Profile-gated tooling plus the fixed advice gives the default boot a truthful 404 for a route that was never supposed to be there:"""),
                        code("four:dev-yml", "yaml",
                                "server/src/main/resources/application.yml — the dev-only document: outside dev these lines do not load, so /graphiql is genuinely unrouted",
                                "server/src/main/resources/application.yml", """
                                ---
                                spring:
                                  config:
                                    activate:
                                      on-profile: dev
                                  graphql:
                                    graphiql:
                                      enabled: true""")),
                section("failure-injection", "Failure injection: chaos at toy scale",
                        "If failures are a first-class output, you must be able to PRODUCE them on demand — the scenario store's older sibling, now with its own explorer.",
                        text("inj:why", """
                                You cannot test what you cannot summon. The moment this app committed to "every failure is a typed, rendered envelope", it owed itself a way to make each failure happen ON COMMAND — for the demos, the curl tours, and the day something regresses and you want the 409 shape back in ten seconds. That discipline already existed at the scenario level (PATCH /api/demo/scenario flips data mode and injects failures per area — the testing surface every data topic reuses); this topic gives the FAILURE TAXONOMY its own injector. The difference matters: the scenario store toggles what the ADAPTERS do; the injector produces the RENDERED FAILURE ITSELF, through the real pipeline, so what you observe is exactly what a client would.

                                The honesty rule for a failure injector: never fabricate the envelope. The endpoint does not build an error JSON and return it — that would test nothing but string building. It runs a real use case returning a real Failed value; the edge converts it to the typed exception; the ADVICE renders it. Every status, code, message and merged detail you see came through the production path. The use case is deliberately boring — one canonical failure per kind, described in the same descriptors the catalog reads:"""),
                        code("inj:usecase", "java",
                                "guide/domain/errors/InjectFailure.java — the injector's single source: canonical failures per kind, shared with the catalog",
                                "server/src/main/java/guide/domain/errors/InjectFailure.java", """
                                public Result<Void> execute(FailureKind kind, boolean includeDetails) {
                                    return Result.failed(failureOf(kind, includeDetails));
                                }

                                // … one Descriptor per kind: whenItFires, message, optional details
                                case CONFLICT -> new Descriptor(
                                        "the request lost a race — duplicate unique key, stale version",
                                        "a post with slug \\"first-post\\" already exists",
                                        Map.of("slug", "first-post"));
                                // … INTERNAL's Descriptor carries null details: the 500 never leaks internals
                                case INTERNAL -> new Descriptor(
                                        "a genuine bug — an unhandled fault, generic on the wire",
                                        "an injected bug — deliberate, for this demo",
                                        null);"""),
                        text("inj:route-intro", """
                                And the endpoint itself is five lines of the same pattern every controller uses — bind, call ONE use case, unwrap-or-throw. An unknown or missing kind is itself a typed validation failure, the taxonomy policing its own door:"""),
                        code("inj:route", "java",
                                "guide/presentation/demo/ErrorsDemoController.java — the inject endpoint: it genuinely fails, and that is the feature",
                                "server/src/main/java/guide/presentation/demo/ErrorsDemoController.java", """
                                @PostMapping(value = "/api/demo/errors/inject",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                Void inject(@RequestBody(required = false) InjectRequest request) {
                                    FailureKind kind = parseKind(request == null ? null : request.kind());
                                    boolean includeDetails = request == null || request.includeDetails() == null
                                            || request.includeDetails();
                                    return unwrap(injectFailure.execute(kind, includeDetails));
                                }"""),
                        text("inj:catalog-trick", """
                                Single-source, made concrete: ReadFailureCatalog derives its example-message column by CALLING the injector's descriptors and its raisedBy column by invoking AppException.of — the same factory the edge uses — so the catalog cannot lie about what inject will do.

                                THE EXPLORER below wires the three endpoints together — browse the ten rows, fire any kind, watch kind → status → envelope (or problem+json) render live. Two design notes it makes visible. First, includeDetails is a first-class toggle because details are a CONTRACT decision, not decoration: watch NOT_FOUND merge postId beside the canonical keys, and watch INTERNAL stay detail-free even when you ask — the 500's leak discipline holds under injection too. Second, the framework rows are marked NOT injectable, with a reproduce command instead — those failures are raised BEFORE any handler runs, by the router itself; no use case can produce them, and pretending otherwise would fake the one thing this demo promises is real. You produce them the honest way: an unrouted path, or the right path with the wrong verb."""),
                        text("inj:client", """
                                The client side of the explorer has one interesting decision, and it is the mirror of everything above: the fetch that calls /inject must treat non-2xx answers as DATA, not as transport failures. The app's guarded pipeline turns every error envelope into a typed client Failure (correct for real screens); this one fetch captures status, content-type and body — observing the failure IS the demo. Note what it does NOT do: build a fake envelope, or special-case statuses:"""),
                        code("inj:client-ts", "ts",
                                "client/src/ui/demos/errors/explorer_demo.tsx — the one fetch that treats the rendered envelope as data",
                                "client/src/ui/demos/errors/explorer_demo.tsx", """
                                async function observeJson(
                                  config: AppConfig,
                                  path: string,
                                  init?: RequestInit,
                                ): Promise<Observed> {
                                  let response: Response;
                                  try {
                                    response = await fetch(`${config.apiBaseUrl}${path}`, {
                                      headers: { 'content-type': 'application/json', accept: '*/*' },
                                      ...init,
                                    });
                                  } catch (error) {
                                    throw new NetworkException('Could not reach the server.', { cause: error });
                                  }
                                  // … parse the body, return {status, contentType, body} — ok or not
                                }"""),
                        demo("inj:explorer-demo", "errors:explorer", "The failure explorer",
                                "Browse the ten-row catalog (eight kinds plus the framework 404/405 rows), inject any kind with or without details, and render the same failure through both contracts — the envelope and RFC 9457 problem+json — side by side. Every answer you see is the real advice rendering a real typed exception.")),
                section("what-logs-when-things-fail", "What logs when things fail",
                        "One level policy, grounded in what the handler actually logs — warn for theirs, error for ours, never payloads — plus the requestId join key.",
                        text("log:policy", """
                                The policy is one sentence, and the advice enforces it line by line: 4XX LOGS WARN (the client's problem — worth the paper trail, not the pager), 5XX LOGS ERROR (ours — someone gets woken up for it in a real system), and EVERY line carries the requestId. The split is not aesthetic — it is the difference between a log you can grep during an incident and a log that pages nobody for anything or everybody for everything. Concretely, from the workhorse branch:"""),
                        code("log:split", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the level policy in five lines: warn with message for theirs, error with stack for ours",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                if (statusCode >= 500) {
                                    log.error("request failed: kind={} status={} requestId={}",
                                            failure.kind(), statusCode, requestId, exception);
                                } else {
                                    log.warn("request failed: kind={} status={} requestId={} message={}",
                                            failure.kind(), statusCode, requestId, failure.message());
                                }"""),
                        text("log:policy-reading", """
                                Same shape, different arms: warn carries the MESSAGE (the client's fault is legible — show what they sent wrong), error carries the EXCEPTION (a stack trace is the beginning of diagnosis; for a 400 it is noise). The framework branch follows the identical split — NoResourceFoundException logs warn at 404, because "this URL does not exist" is not an incident — and the problem-detail branch logs the SAME lines plus rendition=problem-detail, so a route's contract choice never hides its failure from the log.

                                WHAT NEVER LOGS is the other half of the policy, and it is a SECURITY rule (the security topic's threat-model habit applied to logs): no request payloads, no rejected values, no secrets. The validation branch is the worked example — field NAMES on the log line, messages and rejected values only in the response:"""),
                        code("log:fields", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the never-log-values rule: field NAMES on the line, rejected values only on the wire",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                log.warn("request failed: kind=validation status=400 requestId={} fields={}",
                                        requestId, fieldErrors.keySet());"""),
                        text("log:join", """
                                Read that as the general discipline: a log line should say WHAT failed and WHERE, never WHAT THE USER TYPED. Passwords, tokens, PII (the security topic's crypto-shred vault exists because logs are the leak surface people forget) — none of it belongs in a log you keep and index. The catch-all completes the picture: for a truly unknown fault the WIRE gets the generic message, the LOG gets everything.

                                The requestId makes the section operational. Every failure line carries it; every error envelope (and every problem+json rendition — it rides as an extension member) echoes it to the CLIENT; the request-logging filter from the middleware topic stamps SUCCESS lines too. The join is one grep: a user reports "conflict, requestId 3f9a…", you grep, and the request's story unspools — arrival, the warn line, and (for streaming and async paths) the process-net line from the next section. Level policy tells you WHO owns a failure; the requestId tells you WHERE it happened. The observability topic (when it lands) builds the JSON-logging machinery — the levels and the join key are the parts that survive any log format.""")),
                section("process-level-safety-nets", "Process-level safety nets",
                        "Below the request path: the JVM-wide uncaught-exception handler that makes silent thread deaths legible, and the graceful-shutdown story that finishes in-flight requests.",
                        text("net:below-the-request", """
                                Everything so far assumed a REQUEST was failing, so the advice could render it. Threads fail OUTSIDE requests too — and there, no DispatcherServlet, no advice, no envelope exists. A fire-and-forget Runnable whose last line explodes; a virtual thread that outlives its request; an executor task that threw before your Future.get ever runs — without preparation, each of those produces exactly one thing: a stack trace on stderr, mixed into the container's firehose, with no requestId, no correlation, and (for virtual threads especially, where thread churn is high) no guarantee anyone reads it. The JVM's extension point is Thread.setDefaultUncaughtExceptionHandler — one callback, invoked for any thread that dies with an uncaught exception and no handler of its own. This app registers one at boot:"""),
                        code("net:handler", "java",
                                "guide/domain/errors/UncaughtFailureLogger.java — the net under every thread: log with the requestId, then delegate; never swallow",
                                "server/src/main/java/guide/domain/errors/UncaughtFailureLogger.java", """
                                @Override
                                public void uncaughtException(Thread thread, Throwable throwable) {
                                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                                    log.error("uncaught exception (process net): thread={} requestId={}",
                                            thread.getName(), requestId != null ? requestId : "n/a", throwable);
                                    if (previous != null) {
                                        previous.uncaughtException(thread, throwable);
                                    }
                                }"""),
                        text("net:handler-reading", """
                                Read what it does and does not do. It LOGS with the request id — pulled from MDC, which means it correlates automatically when the dying thread still carries the request's context, and honestly reports "n/a" when it does not (a background worker was never part of a request; pretending otherwise would be a worse lie than the dash). It does NOT swallow: after logging it delegates to whatever handler was installed before us, and the thread still dies — an uncaught handler cannot rescue anything, it can only make the death LEGIBLE. That is the right ambition: never let a failure vanish silently, never pretend you absorbed it. Registration is a bean-creation side effect with an idempotence guard (repeated context loads — tests boot contexts constantly — return the already-installed handler instead of chaining duplicates, which would double-log every death):"""),
                        code("net:install", "java",
                                "guide/config/ErrorsDemoConfig.java — registration as a bean side effect, idempotent against repeated context loads",
                                "server/src/main/java/guide/config/ErrorsDemoConfig.java", """
                                /** Creating the bean installs it as the JVM-wide default (see the class). */
                                @Bean
                                UncaughtFailureLogger uncaughtFailureLogger() {
                                    return UncaughtFailureLogger.install();
                                }"""),
                        text("net:adjacent-machinery", """
                                The unit contract is pinned by a test that triggers the handler DIRECTLY — no thread dies in the suite; the handler is called with a thread, a throwable, and a recording stand-in for its predecessor, and the assertion is that the predecessor still ran (the net logs, never swallows) and nothing escaped.

                                Two adjacent mechanisms complete the story, each owned by another topic. @Async failures do NOT reach the default handler while a Future is held: the exception rides the Future and surfaces at get() (the jobs topic's console shows both paths); only a fire-and-forget @Async void falls through to AsyncUncaughtExceptionHandler. If your async work matters, catch it explicitly or dead-letter it through the jobs queue — the uncaught handler is the net BELOW your nets.

                                GRACEFUL SHUTDOWN is the other side of process safety — failures the PROCESS initiates. SIGTERM arrives (a deploy, a scale-down), and the difference between "connection dropped mid-response" and "drained, then exited" is two lines of configuration: server.shutdown=graceful plus a timeout makes Spring stop accepting new requests and FINISH the in-flight ones before the JVM exits. The deployment topic owns the full story when it lands — read this section as its prologue: the uncaught handler makes deaths legible, graceful shutdown prevents the deaths we cause.""")),
                section("terminal-companion", "The error taxonomy from a terminal",
                        "curl the catalog, inject every kind, meet the fixed 404 and 405, and watch one failure wear both contracts.",
                        text("term:tour", """
                                The explorer is a thin shell over three endpoints plus the framework's own misses, so the whole topic reproduces from a terminal — and the terminal shows what the UI abstracts: raw status lines, raw content types, the Allow header on the 405. Start the server (docker compose up, or run GuideApplication), then take the tour. Watch for the content-type flip on the problem+json probe (application/problem+json, not application/json) and for the unrouted path answering 404 — the fix this topic exists to teach, verifiable in one command:"""),
                        code("term:curl-catalog", "bash", "the catalog: all ten rows, then one kind's full taxonomy line", null, """
                                # The whole taxonomy as data — eight kinds + the framework family:
                                curl -s localhost:8080/api/demo/errors/catalog

                                # One kind's full row (whenItFires, example message, reproduce hint):
                                curl -s localhost:8080/api/demo/errors/catalog | grep -o '{[^}]*CONFLICT[^}]*}'"""),
                        code("term:curl-inject", "bash", "inject each kind: the status line IS the taxonomy", null, """
                                # One kind, details included — note the 409 and the merged slug:
                                curl -is -X POST localhost:8080/api/demo/errors/inject \\
                                  -H 'content-type: application/json' \\
                                  -d '{"kind":"CONFLICT","includeDetails":true}'

                                # The same kind without details — envelope shrinks to the canonical keys:
                                curl -is -X POST localhost:8080/api/demo/errors/inject \\
                                  -H 'content-type: application/json' -d '{"kind":"CONFLICT"}'

                                # The whole taxonomy in a loop — one status line per kind:
                                for k in VALIDATION UNAUTHORIZED FORBIDDEN NOT_FOUND \\
                                         CONFLICT RATE_LIMITED UNAVAILABLE INTERNAL; do
                                  curl -s -o /dev/null -w "$k -> %{http_code}\\n" -X POST \\
                                    localhost:8080/api/demo/errors/inject \\
                                    -H 'content-type: application/json' -d "{\\"kind\\":\\"$k\\"}"
                                done

                                # An unknown kind is itself a typed validation failure:
                                curl -is -X POST localhost:8080/api/demo/errors/inject \\
                                  -H 'content-type: application/json' -d '{"kind":"NOPE"}'"""),
                        code("term:curl-misses", "bash", "the framework family: the fixed 404, the 405 with Allow, produced the honest way", null, """
                                # The fixed 404 — unrouted path, NoResourceFoundException through
                                # the advice's framework branch: its own status, our envelope:
                                curl -i localhost:8080/api/definitely/not/routed

                                # The 405 — right path, wrong verb; Allow survives:
                                curl -i -X POST localhost:8080/api/demo/errors/catalog

                                # Contrast: a ROUTED miss — handler ran, typed not_found Result:
                                curl -i localhost:8080/api/content/no-such-topic"""),
                        code("term:curl-problem", "bash", "the same failure in both contracts — watch the content-type", null, """
                                # Our envelope — the app-wide client contract:
                                curl -is -X POST localhost:8080/api/demo/errors/inject \\
                                  -H 'content-type: application/json' -d '{"kind":"CONFLICT"}'

                                # RFC 9457 problem+json — the standard interop shape, same failure:
                                curl -i 'localhost:8080/api/demo/errors/problem-detail?kind=CONFLICT&includeDetails=true'""")));
    }
}
