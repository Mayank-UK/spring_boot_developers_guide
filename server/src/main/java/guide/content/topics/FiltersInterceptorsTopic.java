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
 * Topic 08 — Filters, Interceptors, and Middleware. Like topic 06, this
 * topic ADDS real machinery to the app before teaching it: two servlet
 * filters (MiddlewareOrderingFilter @Order(20) and PipelineTraceFilter at
 * an explicit FilterRegistrationBean order 30) plus the
 * PipelineTraceInterceptor, all in guide/core/middleware, wired by
 * config/MiddlewareDemoConfig. Every request now carries a marker trace
 * collected through request attributes, and the middleware:pipeline demo
 * serves the trace of the very request that asked for it — plus the
 * previous request's full unwind from a bounded ring, and the filter chain
 * introspected live from the bean factory. Every snippet below is quoted
 * from that real code; SnippetSourcesTest verifies each quote mechanically.
 */
public final class FiltersInterceptorsTopic {

    private FiltersInterceptorsTopic() {
    }

    public static Topic topic() {
        return new Topic("filters-interceptors", "Filters, Interceptors, and Middleware",
                "The pipeline every request rides before any controller runs — one real "
                        + "instrumented chain: servlet filters, a handler interceptor, and the "
                        + "controller advice that shapes every failure, proven live from its own trace.",
                TopicGroupId.MIDDLEWARE_REQUEST, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-middleware-model", "The middleware model",
                        "One request, N layers, each wrapping the next — and this app's own chain, instrumented so the demo can show you the very request you are reading.",
                        text("mw:model:chain", """
                                Concept first: a backend is a function from request to response, and NO request ever calls that function directly. Between the socket and the handler sits a pipeline, and in Spring that pipeline has three distinct neighborhoods: the servlet FILTER CHAIN (raw jakarta.servlet territory, owned by the container), Spring MVC's DISPATCHER SERVLET (routing, adapters, view rendering), and the mapped HANDLER with its interceptor wrapping (your controller code, plus HandlerInterceptors). A filter is just a class implementing one method — doFilter(request, response, chain) — and the whole model lives in what that third parameter means: the chain is not a queue, it is a composition. Each filter holds the REST of the pipeline — every later filter, the dispatcher, your handler — and the line chain.doFilter(request, response) invokes it. Code before that line runs on the way IN; code after it returns runs on the way OUT, after everything downstream has finished.

                                That symmetry is the model's whole power: a filter does not "call the next middleware and hope" — it WRAPS it. Entry order is exit order, mirrored: the first filter in is the last one out, so an "outer" filter sees the request before anyone and the response after everyone. If a filter never calls chain.doFilter, the pipeline stops there — the filter has taken ownership of the response (that is how early rejects work). Compare the express middleware you may know: there, middleware is a stack you ADVANCE by calling next(), and after next() returns you are also unwinding — the shapes converge. Two real differences are worth carrying: express middleware is framework-level (it sees routing abstractions), while servlet filters are deliberately dumber — they see bytes, headers, and streams, nothing about controllers; and the servlet model is strictly nested rather than queue-like, which is why "after" work belongs in a finally block — an exception thrown deep inside the pipeline still unwinds every filter that already entered.

                                This repo now carries that lesson as living code. Every request through this app appends markers — "filter:ordering:before", "interceptor:preHandle", and so on — into a trace stored as a REQUEST ATTRIBUTE, and the demo at the end of this section serves the trace captured for the request that fetched it."""),
                        code("mw:model:bookend", "java",
                                "guide/core/middleware/MiddlewareOrderingFilter.java — the outermost of this topic's filters: wrap proof plus the ring's bookend",
                                "server/src/main/java/guide/core/middleware/MiddlewareOrderingFilter.java", """
                                @Override
                                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                        throws ServletException, IOException {
                                    PipelineTrace.mark(request, BEFORE_MARKER);
                                    try {
                                        chain.doFilter(request, response);
                                    } finally {
                                        PipelineTrace.mark(request, AFTER_MARKER);
                                        fileCompletedTrace(request, response);
                                    }
                                }
                                // …
                                private void fileCompletedTrace(HttpServletRequest request, HttpServletResponse response) {
                                    Object stamped = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
                                    String requestId = stamped instanceof String value ? value : null;
                                    try {
                                        traceLog.record(new CompletedTrace(requestId, request.getMethod(), request.getRequestURI(),
                                                response.getStatus(), PipelineTrace.markers(request)));
                                    } catch (RuntimeException instrumentationFailure) {
                                        log.debug("pipeline trace filing skipped: {}", instrumentationFailure.getMessage());
                                    }
                                }"""),
                        text("mw:model:demo", """
                                Read that filter slowly, because it encodes three deliberate decisions. First, the marker pair: BEFORE_MARKER lands before the chain continues and AFTER_MARKER in a finally — the wrap, made visible; even a request that explodes downstream still gets its after-marker and its ring entry. Second, the ring: this filter is @Order(20), the OUTERMOST of this topic's pair, so its finally is the last of this topic's code a request executes — which makes it the only place that can file the FULLY unwound marker sequence (filters, interceptor, handler, everything's exit markers) into the bounded PipelineTraceLog. Third, the defensive catch: by the time fileCompletedTrace runs, the response left this filter's control long ago; instrumentation must never take a completed request down with it, so a filing failure is logged and swallowed — the one catch-and-continue this guide allows, because nothing downstream is left waiting.

                                The demo endpoint GET /api/demo/middleware/pipeline returns four things: currentRequestTrace — the markers captured for THIS very request, from first filter entry up to the handler (the handler appends its own "you are here" marker from inside the controller method); previousCompletedTrace — the most recent COMPLETED request's eight-marker unwind from the ring, which might be your previous fetch or anyone else's request (any endpoint flows the same chain); registeredFilters — the servlet filters the running context really registers, with their real orders; and analysis — findings derived from the actual markers, including what any missing marker would mean. Why does the current trace stop at the handler? Because postHandle, afterCompletion and both filter after-markers run AFTER the response is serialized — they cannot be inside the body they trail. Press run again: your first fetch appears in the previousCompletedTrace slot, unwind complete:"""),
                        code("mw:model:analysis", "java",
                                "guide/domain/middleware/TraceAnalysis.java — findings derived from what the machinery recorded, never from assumptions",
                                "server/src/main/java/guide/domain/middleware/TraceAnalysis.java", """
                                if (orderingBefore >= 0 && traceBefore >= 0) {
                                    findings.add(orderingBefore < traceBefore
                                            ? "filter:ordering:before precedes filter:trace:before — @Order(20) ran before @Order(30): "
                                                    + "the LOWER order value enters the chain earlier, and the unwind mirrors it (the "
                                                    + "@Order(30) filter's after-marker precedes the @Order(20)'s)."
                                            : "the @Order(20) marker follows the @Order(30) one — the chain did not apply the declared "
                                                    + "order (a custom registration or an ordering override is in play).");
                                }"""),
                        demo("mw:model:demo-block", "middleware:pipeline", "The pipeline, captured live",
                                "Fetches GET /api/demo/middleware/pipeline and shows the marker sequence captured for that very request (filter before → interceptor preHandle → handler), the previous completed request's full eight-marker unwind from the bounded ring, the registered filter chain introspected from the live bean factory (name, order, registration style — requestIdFilter's 2147483647 included, honestly), and the analysis derived from the markers. Run it again and your previous fetch lands in the completed slot.")),
                section("once-per-request-filter", "OncePerRequestFilter, ordering, registration",
                        "Writing a filter the Boot way: extend OncePerRequestFilter, state an order, pick a registration style — and know what your position means.",
                        text("mw:opr:once", """
                                The raw contract is jakarta.servlet.Filter with its doFilter method, but in a Boot app you should essentially never implement it directly — extend OncePerRequestFilter instead, for a reason the servlet spec makes non-obvious: a plain Filter is invoked once per DISPATCH, not once per request. When a servlet forwards or includes, and — more commonly — when an exception escapes to the container and the error page mechanism re-dispatches as an ERROR dispatch, the chain runs AGAIN. Your request-id generator would stamp a second id over the first; your metrics filter would double-count the request; your auth redirect would loop. OncePerRequestFilter solves it mechanically: before invoking your doFilterInternal it checks a request attribute recording "this filter already ran for this request", and skips the re-run. Boot's own filters — character encoding among them — are built on it for exactly this reason. The second, smaller gift is ergonomics: doFilterInternal receives already-typed HttpServletRequest/Response parameters, with no instanceof ceremony.

                                This topic's teaching filter is deliberately the smallest honest one possible — two string appends bracketing the chain:"""),
                        code("mw:opr:trace-filter", "java",
                                "guide/core/middleware/PipelineTraceFilter.java — the whole filter: a marker before, a marker after, nothing else",
                                "server/src/main/java/guide/core/middleware/PipelineTraceFilter.java", """
                                public class PipelineTraceFilter extends OncePerRequestFilter {

                                    public static final String BEFORE_MARKER = "filter:trace:before";
                                    public static final String AFTER_MARKER = "filter:trace:after";

                                    @Override
                                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                            throws ServletException, IOException {
                                        PipelineTrace.mark(request, BEFORE_MARKER);
                                        try {
                                            chain.doFilter(request, response);
                                        } finally {
                                            PipelineTrace.mark(request, AFTER_MARKER);
                                        }
                                    }
                                }"""),
                        text("mw:opr:order", """
                                POSITION is semantics: the chain is a sorted list, ordered by each registration's order value — LOWER runs earlier on the way in, and (the mirror) later on the way out. A request-id filter usually wants to be outermost so every later layer can log with the id; a character-encoding filter MUST be first, because every downstream reader decodes bytes through it. Two registration styles dominate, and this app deliberately demonstrates both. Style one: a plain @Bean Filter whose class carries @Order — Boot auto-registers it and sorts it by the annotation; that is MiddlewareOrderingFilter above. Style two: an explicit FilterRegistrationBean wrapping the filter instance — you set the order in code at the composition root, and you additionally own URL patterns, the servlet name, and enabled(false) for suppressing registration; that is PipelineTraceFilter's registration below. One rule Boot enforces for you: when a filter bean is covered by a registration bean, it is registered exactly once — through the registration — so the two styles compose safely.

                                And what of the filter that has been here since topic 04? RequestIdFilter declares NO order anywhere — and "no order" is itself a position: Boot sorts unordered filters at LOWEST_PRECEDENCE (2147483647), AFTER every filter that declares a number. That is its real position, and the demo's registeredFilters table reports it honestly rather than flattering the design. Nothing breaks — every filter still precedes the handler — but if id-stamping should be outermost, the fix is one annotation; quoting the truth is the point:"""),
                        code("mw:opr:request-id", "java",
                                "guide/core/logging/RequestIdFilter.java — the app's first filter, unordered and honest about it: attribute + header + MDC, cleared in finally",
                                "server/src/main/java/guide/core/logging/RequestIdFilter.java", """
                                @Override
                                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                                        throws ServletException, IOException {
                                    String requestId = request.getHeader(HEADER);
                                    if (requestId == null || !SAFE_ID.matcher(requestId).matches()) {
                                        requestId = UUID.randomUUID().toString();
                                    }
                                    request.setAttribute(REQUEST_ATTRIBUTE, requestId);
                                    response.setHeader(HEADER, requestId);
                                    MDC.put(MDC_KEY, requestId);
                                    try {
                                        filterChain.doFilter(request, response);
                                    } finally {
                                        MDC.remove(MDC_KEY);
                                    }
                                }"""),
                        code("mw:opr:config", "java",
                                "guide/config/MiddlewareDemoConfig.java — both registration styles side by side, wired at the composition root",
                                "server/src/main/java/guide/config/MiddlewareDemoConfig.java", """
                                @Bean
                                MiddlewareOrderingFilter middlewareOrderingFilter(PipelineTraceLog pipelineTraceLog) {
                                    return new MiddlewareOrderingFilter(pipelineTraceLog);
                                }

                                @Bean
                                FilterRegistrationBean<PipelineTraceFilter> pipelineTraceFilterRegistration() {
                                    FilterRegistrationBean<PipelineTraceFilter> registration =
                                            new FilterRegistrationBean<>(new PipelineTraceFilter());
                                    registration.setOrder(30);
                                    return registration;
                                }"""),
                        text("mw:opr:defensive", """
                                Three defensive rules for anything that runs on every request — and in a Boot app that is literally every request, actuator probes included. CHEAP: the chain is the front door; a filter that does I/O, reflection, or per-request allocation on the hot path taxes every endpoint at once. This topic's pair prices in at a few string appends and one ring filing. NEVER THROW YOUR OWN: a filter's only legitimate exceptions are the ServletException/IOException the chain below it produced — anything else you rethrow wrapped, never swallowed, because swallowing here hides failures from both the client and the advice (next section). AFTER-WORK IN FINALLY: markers, timers, MDC cleanup, ring filings — anything that must mirror entry goes in a finally block, or the first downstream exception silently skips it. The only sanctioned catch-and-continue is instrumentation that has already left the request's critical path (the ring filing above), and even that logs at debug — recorded, not erased.""")),
                section("handler-interceptor", "The handler interceptor",
                        "After the mapping: interceptors wrap mapped HANDLERS, not raw requests — three phases, three different contracts.",
                        text("mw:int:contracts", """
                                Filters are blind to MVC on purpose — by the time they run, no handler has been chosen. The HandlerInterceptor is the cross-cutting seam on the OTHER side of the DispatcherServlet: it runs only after a handler mapping claimed the request (a request no mapping claims — not even Boot's /** static-resource fallback — never gets a preHandle), and it can see WHICH handler was chosen. It has exactly three phases. preHandle(request, response, handler) runs BEFORE the handler method: return true and the pipeline continues; return false and you have taken ownership — the handler and later interceptors are skipped, and you are expected to have written some response. That boolean is the interceptor's only veto, and it is where mapping-aware authorization checks live (topic 24). postHandle runs AFTER the handler method returned but BEFORE view rendering — and it is SKIPPED entirely when the handler threw, because there is no successful return to follow. In classic server-side MVC it is your last chance to adjust the ModelAndView; for a @ResponseBody REST handler, honesty required: the message body was already serialized inside handler processing, so postHandle is TOO LATE to change the body — it remains useful for cheap post-facts (audit, header stamps when the response is not yet committed). afterCompletion runs in the dispatcher's finally: ALWAYS, even when the handler threw, for every interceptor whose preHandle returned true, in REVERSE registration order — the interceptor's cleanup hook, with the exception (if any) handed to it as a parameter.

                                This topic's interceptor is one marker per phase — the smallest instrument that makes all three contracts visible in a single trace:"""),
                        code("mw:int:interceptor", "java",
                                "guide/core/middleware/PipelineTraceInterceptor.java — all three phases, one marker each",
                                "server/src/main/java/guide/core/middleware/PipelineTraceInterceptor.java", """
                                @Override
                                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                                    PipelineTrace.mark(request, PRE_HANDLE_MARKER);
                                    return true;
                                }

                                @Override
                                public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                                        @Nullable ModelAndView modelAndView) {
                                    PipelineTrace.mark(request, POST_HANDLE_MARKER);
                                }

                                @Override
                                public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                        @Nullable Exception ex) {
                                    PipelineTrace.mark(request, AFTER_COMPLETION_MARKER);
                                }"""),
                        code("mw:int:registration", "java",
                                "guide/config/MiddlewareDemoConfig.java — interceptors register with the MVC registry, not the servlet context",
                                "server/src/main/java/guide/config/MiddlewareDemoConfig.java", """
                                @Bean
                                PipelineTraceInterceptor pipelineTraceInterceptor() {
                                    return new PipelineTraceInterceptor();
                                }

                                @Bean
                                WebMvcConfigurer pipelineTraceInterceptorRegistration(PipelineTraceInterceptor interceptor) {
                                    return new WebMvcConfigurer() {
                                        @Override
                                        public void addInterceptors(InterceptorRegistry registry) {
                                            registry.addInterceptor(interceptor).addPathPatterns("/api/**");
                                        }
                                    };
                                }"""),
                        text("mw:int:judgment", """
                                The registration itself teaches the split: filters register with the SERVLET context (the FilterRegistrationBean you saw), interceptors with the MVC REGISTRY via a WebMvcConfigurer — and path patterns are first-class there ("/api/**" is why an /actuator request shows filter markers but no interceptor markers in the demo's ring). Interceptors run in registration order and unwind in reverse; registry.addInterceptor(...).order(n) exists when several interceptors' phases must interleave deliberately.

                                So what belongs in an interceptor rather than a filter? Anything that needs to KNOW THE HANDLER. The handler parameter is usually a HandlerMethod — you can read the controller class, the method, its annotations; per-route authorization, per-handler metrics with the operation name, skipping instrumentation for specific controllers, adjusting the model for specific views. What belongs in a filter rather than an interceptor? Everything raw: encoding, request logging at the byte level, correlation ids, multipart shaping, anything that must also cover non-MVC dispatches (static resources, the error dispatch, endpoints the mapping never routes). And nothing business-shaped belongs in either — a business rule hidden in a pipeline layer is a rule the reader of the use case cannot find.

                                One survey-grade warning: interceptors and ASYNC do not compose simply. When a handler returns a deferred result, the request thread is released and later re-dispatched (ASYNC dispatch) — preHandle runs again on that dispatch, and a plain filter re-runs too (OncePerRequestFilter is what protects you); AsyncHandlerInterceptor adds afterConcurrentHandlingStarted for the hand-off moment. Topic 28 (realtime) treats async in depth — here the rule is just: with deferred results, verify your pipeline assumptions empirically, exactly as this topic's demo does.""")),
                section("controller-advice-cross-cutting", "Cross-cutting the error path: @ControllerAdvice",
                        "The error path has middleware too — @ControllerAdvice is the envelope around every handler's failure. This app's GlobalExceptionHandler, dissected from its own source.",
                        text("mw:advice:model", """
                                There is a third cross-cutting seam, and it took care of you before you ever wrote a filter: when any handler throws, the DispatcherServlet consults its exception resolvers, and @ExceptionHandler methods inside a @ControllerAdvice class are the resolver's global arm — selected by exception type across EVERY controller, not just the one that threw. @RestControllerAdvice is that plus @ResponseBody: the advice writes JSON. It is the express error middleware's exact counterpart: ONE place that shapes every failure, so no controller ever hand-builds an error response. This app's contract, stated in topic 04 and enforced everywhere: typed AppExceptions contribute their failure's status, code, message, and details into one envelope — { error: { code, message, requestId, ... } }; a body that never parsed as JSON maps to a 400 validation envelope; anything unknown becomes a 500 with a generic message on the wire and the full stack in the log. Nothing is swallowed; 4xx logs warn (the client's problem), 5xx logs error (ours):"""),
                        code("mw:advice:handler", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the envelope for typed failures, and the id fallback that reaches for the filter's work",
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
                                }
                                // …
                                private static String requestId(HttpServletRequest request) {
                                    Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
                                    if (attribute instanceof String value && !value.isEmpty()) {
                                        return value;
                                    }
                                    String mdc = MDC.get(RequestIdFilter.MDC_KEY);
                                    return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
                                }"""),
                        text("mw:advice:pipeline", """
                                Place the advice in the pipeline you now know: it runs INSIDE the DispatcherServlet — inside the interceptor's afterCompletion, inside every filter. Two consequences you can watch happen. First, the advice CONSUMES the exception: by the time the interceptor's afterCompletion sees the request, the failure is already an envelope with a status code, and its ex parameter is null — afterCompletion's exception parameter only carries exceptions the resolver did not handle. Second, the chain still unwinds: the response the advice wrote travels out through afterCompletion, then through both filter after-markers, then into the ring — with the ENVELOPE's status, not a 500 (unless the envelope is a 500). That is the layered error design this guide uses end to end: failures travel as values through the middle layers, and become one consistent envelope exactly at the edges. Topic 30 owns failure handling in depth; what belongs here is only the geometry — the advice is the error path's middleware, and it composes with the rest of the pipeline like any other layer.""")),
                section("what-starters-mount", "What the starters mount",
                        "Boot assembles a filter chain before you write a line — here is what is on this app's chain, and how to SEE the real chain instead of guessing.",
                        text("mw:mount:chain", """
                                Ask what is already on the chain and the honest answer is: more than you added. On this app — visible right now in the demo's table — Boot's autoconfiguration opens the chain with the OrderedCharacterEncodingFilter at HIGHEST_PRECEDENCE (first, necessarily: everything downstream decodes bytes through it), then the web observation filter the actuator stack registers at HIGHEST_PRECEDENCE + 1 (Micrometer's per-request instrumentation — where endpoint metrics begin), the formContentFilter at -9900 (reshaping PUT/PATCH form bodies), and the requestContextFilter at -105 (opening the request context request-scoped beans need). Actuator ENDPOINTS themselves are ordinary MVC handlers riding the same chain — no magic filter of their own. The biggest resident is a placeholder: when Spring Security arrives in topic 23, it mounts a single FilterChainProxy (via a DelegatingFilterProxyRegistrationBean) at a deliberately early position, and that one servlet filter internally delegates to a chain of its own — security's whole filter-chain model nests INSIDE one entry of the servlet chain you are reading about. Request logging, meanwhile, ships in the box as an OPT-IN: CommonsRequestLoggingFilter exists as a class, mounts only when you declare the bean, and topic 33 mounts it deliberately.

                                The general lesson is that "the filter chain" is not a framework constant — it is the sorted union of autoconfiguration, your @Bean filters, and your registration beans — and you should READ it rather than recite it. This topic's demo does exactly that: its registeredFilters table is introspected live from the running bean factory by the adapter below, which re-derives each filter's effective order with the same precedence the servlet initializer applies when it sorts the chain — the Ordered interface first, then @Order on the class, else LOWEST_PRECEDENCE — and merges explicit registration beans with auto-registered plain filters, deduplicated by instance:"""),
                        code("mw:mount:adapter", "java",
                                "guide/data/middleware/MiddlewareRegistrationAdapter.java — the chain, read from the live bean factory with the initializer's own precedence rule",
                                "server/src/main/java/guide/data/middleware/MiddlewareRegistrationAdapter.java", """
                                @Override
                                public List<RegisteredMiddleware> servletFilters() {
                                    Set<Filter> alreadyRegistered = new HashSet<>();
                                    List<RegisteredMiddleware> middleware = new ArrayList<>();

                                    for (Map.Entry<String, FilterRegistrationBean<?>> entry
                                            : registrationBeans().entrySet()) {
                                        FilterRegistrationBean<?> registration = entry.getValue();
                                        if (!registration.isEnabled()) {
                                            continue;
                                        }
                                        Filter target = registration.getFilter();
                                        alreadyRegistered.add(target);
                                        middleware.add(new RegisteredMiddleware(
                                                entry.getKey(), registration.getOrder(), "FilterRegistrationBean"));
                                    }

                                    for (Map.Entry<String, Filter> entry : context.getBeansOfType(Filter.class).entrySet()) {
                                        Filter filter = entry.getValue();
                                        if (alreadyRegistered.contains(filter)) {
                                            continue;
                                        }
                                        middleware.add(new RegisteredMiddleware(
                                                entry.getKey(), orderOf(filter), "@Bean filter (auto-registered)"));
                                    }

                                    middleware.sort(Comparator.comparingInt(RegisteredMiddleware::order));
                                    return List.copyOf(middleware);
                                }
                                // …
                                private static int orderOf(Filter filter) {
                                    if (filter instanceof Ordered ordered) {
                                        return ordered.getOrder();
                                    }
                                    return OrderUtils.getOrder(filter.getClass(), Ordered.LOWEST_PRECEDENCE);
                                }"""),
                        text("mw:mount:read", """
                                Reading the table is reading the chain: characterEncodingFilter opens it with its negative extreme (HIGHEST_PRECEDENCE); this topic's two filters sit at 20 and 30 with their registration styles labeled; and requestIdFilter closes the app-owned section at 2147483647 — LOWEST_PRECEDENCE, the number that means "declared no position", quoted from the running context rather than from intention. The table is sorted by the same number the runtime sorts by, so what you read IS the execution order. Outside the demo, the traditional way to see the chain is a startup breakpoint in the initializer, or registering a filter that logs the registrations it observes — but a live introspection endpoint like this one has a property neither has: it cannot drift out of date, because it asks the application every time.""")),
                section("custom-middleware-patterns", "Custom middleware patterns",
                        "The patterns this repo actually uses, distilled as recipes — correlation ids, MDC pairing, request attributes over thread state, bounded evidence rings.",
                        text("mw:pat:recipes", """
                                Recipe one: CORRELATION IDS AT THE EDGE. Stamp the id in the outermost layer you control; honor a well-formed incoming id (this app caps it at 128 characters and a safe-charset regex — an id header is still untrusted input) and generate otherwise; then park it where every later layer can reach it: a request attribute (robust, dies with the request), the MDC (so log lines carry it), and the response header (so the caller can quote it back). RequestIdFilter, quoted in full above, is this recipe verbatim. Recipe two: MDC PAIRING. Every MDC.put has a matching remove in a finally — a leaked MDC key on a pooled platform thread lies in every log line that thread writes for the rest of its life; the pairing is not optional hygiene, it is correctness. Recipe three: CORRELATION ACROSS ASYNC. When work crosses a thread boundary — an executor, a CompletableFuture, a message queue — the MDC does not follow it, ever. Pass the id as DATA: an explicit parameter, a message header, a task attribute. This app runs on virtual threads (topic 04), where "the thread" is an even shakier notion, which makes thread-local state a last resort rather than a default.

                                The decision that carries all three recipes: REQUEST ATTRIBUTES OVER THREADLOCAL for per-request state. The trace this topic is built on is one list, born with the request, reachable by every layer that holds the request — filters, interceptors, handlers, the advice — and it cannot leak, because its lifetime IS the request's:"""),
                        code("mw:pat:trace", "java",
                                "guide/core/middleware/PipelineTrace.java — per-request state as a request attribute: every layer sees the same list, nothing leaks across threads",
                                "server/src/main/java/guide/core/middleware/PipelineTrace.java", """
                                public static void mark(HttpServletRequest request, String marker) {
                                    trace(request).add(Objects.requireNonNull(marker, "marker"));
                                }

                                public static List<String> markers(HttpServletRequest request) {
                                    return List.copyOf(trace(request));
                                }
                                // …
                                private static List<String> trace(HttpServletRequest request) {
                                    Object attribute = request.getAttribute(REQUEST_ATTRIBUTE);
                                    if (attribute == null) {
                                        List<String> fresh = new ArrayList<>();
                                        request.setAttribute(REQUEST_ATTRIBUTE, fresh);
                                        return fresh;
                                    }
                                    return (List<String>) attribute;
                                }"""),
                        text("mw:pat:ring", """
                                Recipe four: RECENT-N EVIDENCE. When the useful question is "what did the last few requests actually do" — debugging a pipeline, proving an ordering — keep a bounded ring, not a log scan. Bounded means the memory cost is fixed regardless of traffic: when the ring is full, the oldest entry is evicted, always; every access is synchronized because servlet threads, virtual threads, and async hand-offs all file here; readers take immutable snapshots. The same shape already serves topic 06's invocation log — it is this repo's standard answer to "show me recent behavior" — and this topic's ring files one entry per completed request:"""),
                        code("mw:pat:ring-log", "java",
                                "guide/core/middleware/PipelineTraceLog.java — bounded, guarded, evicts the oldest: the recent-N pattern",
                                "server/src/main/java/guide/core/middleware/PipelineTraceLog.java", """
                                public void record(CompletedTrace trace) {
                                    Objects.requireNonNull(trace, "trace");
                                    synchronized (traces) {
                                        if (traces.size() == capacity) {
                                            traces.pollFirst();
                                        }
                                        traces.addLast(trace);
                                    }
                                }

                                public CompletedTrace latest() {
                                    synchronized (traces) {
                                        return traces.peekLast();
                                    }
                                }"""),
                        text("mw:pat:rules", """
                                The judgment table, compressed: RAW SERVLET CONCERNS — encoding, byte-level logging, correlation, anything that must cover non-MVC dispatches — go in a filter. HANDLER-AWARE CONCERNS — per-route authorization, per-handler metrics with the operation's name, view-model touches — go in an interceptor. THE SHAPE OF FAILURE goes in a controller advice: one envelope, one logging policy, no per-controller error plumbing. METHOD-LEVEL cross-cutting inside the business layers — the trace aspect, @Transactional — is proxy territory, topic 06's domain. This app's current tally is one line each: three filters (request-id, ordering, trace), one interceptor, one advice, one aspect — every one earning its slot by a rule you can state in a sentence.

                                And the defensive distillation, one paragraph: middleware runs on EVERYTHING, so it must be cheap by construction, not by benchmark. It must never invent exceptions — rethrow the chain's, wrap your own, swallow nothing except instrumentation that has already left the request's critical path (and log even that). Exit work belongs in finally blocks. Diagnostics fail OPEN (the request completes without its trace); security fails CLOSED (topic 23 will flip that polarity on purpose). And whenever you add a layer, add the marker that proves it ran — a pipeline you cannot observe is a pipeline you are guessing about.""")),
                section("terminal-companion", "The endpoints from a terminal",
                        "curl the chain: stamp a marker of your own, push a health probe through it, and read both back from the ring.",
                        text("mw:term:tour", """
                                The terminal closes the loop between the prose and the process. Five moves repay hand-typing. First, the demo raw: currentRequestTrace lists exactly four markers for exactly this request — check the order, filter before interceptor before handler, and notice what is absent: nothing after the handler, because the unwind trails the response. Second, immediately repeat the call: previousCompletedTrace is now your FIRST call, eight markers, ending interceptor:afterCompletion, filter:trace:after, filter:ordering:after — in that order, because afterCompletion runs inside the chain and the outermost filter exits last. Third, stamp your own marker: send a well-formed x-request-id and watch it travel the whole pipeline — echoed as a response header by the filter, carried in the body's requestId field, and one call later, filed in the ring's entry. Fourth, push something unrelated through the chain — an actuator health probe — then read the demo again: the ring's latest entry is /actuator/health with FOUR markers (both filters, no interceptor: its pattern is /api/**), proof that the chain serves every request the container sees, not just demo endpoints. Fifth, ask for a path that does not exist: the chain still wraps it — the filters mark it, Boot's fallback mapping even gives the interceptor a preHandle, the resource handler's not-found lands in the advice's unknown-failure envelope (a 5xx), postHandle is skipped because the handler threw, afterCompletion still fires, and the ring remembers the whole six-marker sequence:"""),
                        code("mw:term:curl", "bash", "the curl tour of topic 08", null, """
                                # The pipeline, captured for the very request you are reading:
                                curl -s localhost:8080/api/demo/middleware/pipeline | head -40

                                # … again: previousCompletedTrace is now the FIRST call — full unwind:
                                curl -s localhost:8080/api/demo/middleware/pipeline | grep -o 'afterCompletion'

                                # Your own marker, honored end-to-end (echo header + body + ring):
                                curl -s -i -H 'x-request-id: my-marker-42' localhost:8080/api/demo/middleware/pipeline | grep -i x-request-id
                                curl -s -H 'x-request-id: my-marker-42' localhost:8080/api/demo/middleware/pipeline | grep my-marker-42

                                # Anything through the chain — probe health, then read the ring:
                                curl -s localhost:8080/actuator/health > /dev/null
                                curl -s localhost:8080/api/demo/middleware/pipeline | grep -o '/actuator/health'

                                # A path that does not exist still rides the chain — read its trace:
                                curl -s localhost:8080/api/no-such-path > /dev/null
                                curl -s localhost:8080/api/demo/middleware/pipeline | grep -o 'no-such-path'

                                # This topic's own content, served through the chain it teaches:
                                curl -s localhost:8080/api/content/filters-interceptors | head -60""")));
    }
}
