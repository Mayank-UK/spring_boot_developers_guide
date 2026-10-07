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
 * Topic 07 — Routing. The URL-to-handler table Spring MVC actually runs on,
 * taught from the app's own registry: the mapping annotations and their
 * composed semantics, the path-pattern dialect, controllers as mounted
 * routers, how specificity resolves overlapping patterns (verified against
 * Spring 7's own comparator), what Boot 4 really does with an unrouted path
 * (verified live — including this app's current honest wart), and how the
 * declarative style scales against the functional alternative. Java/YAML/TS
 * blocks quote this repo's real files; http/bash blocks are verified wire
 * and terminal transcripts.
 */
public final class RoutingTopic {

    private RoutingTopic() {
    }

    public static Topic topic() {
        return new Topic("routing", "Routing",
                "From URL to handler: the mapping annotations, the path-pattern "
                        + "dialect, and the specificity rules that decide which method "
                        + "answers — read live from this app's own route table.",
                TopicGroupId.MIDDLEWARE_REQUEST, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-routing-is", "What routing is",
                        "A web framework is a loop that turns \"method + path\" into \"your method\" — routing is the table that makes that decision, and this app's real registry is the specimen.",
                        text("route:concept", """
                                Every HTTP request arrives carrying the two facts routing cares about: a method (GET, POST…) and a path (/api/content/java-language). Everything else — headers, query, body — is cargo for the handler. Routing is the machinery that answers one question for every request: which method of which bean handles this? In Express you wrote that table by hand — app.get('/api/content/:id', handler) appended rows to a list the framework walked per request. In Spring MVC you DECLARE each row with an annotation on a method, and at startup the DispatcherServlet's handler mapping builds the same kind of table. The mental model transfers exactly: @GetMapping is app.get, a @RestController is a bag of routes, and class-level @RequestMapping (later section) is router mounting.

                                Two consequences of "the table is built at startup" are worth internalizing early. First, routing is REFLECTION-SHAPED: Spring scans your beans once, reads @GetMapping values, parses each path into a PathPattern, and registers the parsed form — at request time there is no annotation reading and no string parsing, only pattern matching against precompiled structures. Second, the table is INTROSPECTABLE: the RequestMappingHandlerMapping bean keeps every registered mapping in a map you can read like any other bean dependency. Nothing about your routes is magic or hidden; the framework merely holds them.

                                That second consequence is this topic's demo. The explorer below is not a hand-written list of routes — it is the app's actual registry, read live per request:"""),
                        code("route:adapter", "java",
                                "guide/data/routing/RoutingTableAdapter.java — the one file that reads Spring's registry; every route this app serves, straight from getHandlerMethods()",
                                "server/src/main/java/guide/data/routing/RoutingTableAdapter.java", """
                                for (Map.Entry<RequestMappingInfo, HandlerMethod> registered
                                        : handlerMapping.getHandlerMethods().entrySet()) {
                                    try {
                                        RequestMappingInfo info = registered.getKey();
                                        HandlerMethod handler = registered.getValue();
                                        // …
                                        for (String pattern : sorted(info.getPatternValues())) {
                                            entries.add(new RouteEntry(pattern, methods, controller, bean,
                                                    handlerName, produces, consumes));
                                        }
                                    } catch (RuntimeException skipped) {
                                        // unresolvable mapping — an introspection table reports what it can read
                                    }
                                }"""),
                        text("route:demo-intro", """
                                The explorer has two probes. The TABLE lists every registered mapping — pattern, allowed methods, controller bean, handler method, produces/consumes — with a prefix filter (try /api/demo or /api/content). The MATCH probe takes a method + path you type and explains what Spring would do: the winning pattern, every other pattern that also matched, and WHY the winner won — a ranked specificity order, not just a verdict. Type /api/content/java-language, then /api/demo/routing/table, and compare the answers.

                                One honesty note before you play: the table rows are the real registry, but the match explanation is a teaching model — a pure re-implementation of Spring's PathPattern semantics (matching, captured variables, and the scoring rules verified against Spring 7.0.9, the version this app runs) that lives in the domain layer where it can be read, tested, and quoted. When framework and model ever disagree, the wire wins; the tests pin the cases this app actually serves."""),
                        demo("route:demo", "routing:explorer", "The route explorer, live",
                                "This app's real route table, read live from the DispatcherServlet's registry. Filter by prefix, then probe: give the match panel any method + path and it answers with the winning mapping, the ranked candidates that also matched (exact beats variable beats wildcard beats catch-all — with the scores), captured URI variables, and typed verdicts for method-not-allowed and no-match. The explorer's own controller contributes three live pattern specimens — a regex-constrained variable, a plain variable, and a rest-of-path catch-all — so the ranking story is demonstrated on real routes, not theory."),
                        text("route:specimens", """
                                The three specimen routes the demo controller itself registers are part of the teaching surface: /api/demo/routing/specimens/{id:\\d+} answers only all-digit segments, /api/demo/routing/{section} overlaps the explorer's own exact routes (and loses to them — that overlap is deliberate, the ranking section's live proof), and /api/demo/routing/specimens/{*rest} swallows everything after /specimens. When a later section quotes 'real examples', these are the examples — registered, dispatched, and introspectable in the table above.""")),
                section("mapping-annotations", "The mapping annotations",
                        "@GetMapping and its siblings are @RequestMapping with one attribute pre-filled — and @RequestMapping itself composes across class and method.",
                        text("map:family", """
                                The annotation family is one idea, five shortcuts. @RequestMapping is the general form: its attributes say which paths, methods, params, headers, produces, and consumes a method handles. @GetMapping, @PostMapping, @PutMapping, @PatchMapping, and @DeleteMapping are @RequestMapping(method = …) spelled idiomatically — @GetMapping("/api/health") is EXACTLY @RequestMapping(path = "/api/health", method = RequestMethod.GET); the shortcuts exist because method-scoped endpoints are nearly everything anyone writes. Use the shortcuts; drop to @RequestMapping when a method genuinely answers several methods at once (rare, and worth a comment explaining why one handler spans verbs), or when you need the general form's other attributes together.

                                The app's whole content API is two mappings, and they teach the shape on real code — a fixed path, and a path with one variable segment:"""),
                        code("map:content", "java",
                                "guide/presentation/ContentController.java — the two mappings that serve every topic page you are reading",
                                "server/src/main/java/guide/presentation/ContentController.java", """
                                /** GET /api/content → 200 { version, groups, topics } */
                                @GetMapping("/api/content")
                                TopicList list() {
                                    return unwrap(listTopicSummaries.execute());
                                }

                                /** GET /api/content/{topicId} → 200 { version, ...topic } or 404 envelope */
                                @GetMapping("/api/content/{topicId}")
                                TopicDetail detail(@PathVariable String topicId) {
                                    return unwrap(getTopicById.execute(topicId));
                                }"""),
                        text("map:method-attr", """
                                Notice what is NOT on those annotations: no method attribute (the shortcut fixes it), no produces or consumes. Omitted attributes mean "any": the health mapping below answers any client regardless of Accept, and a body-less GET needs no consumes. Declaring them narrows who may reach the handler — the inspector's POST variant (topic 03) is this app's real example of both declarations at work: it REFUSES bodies that are not JSON (415 before your method runs) and PROMISES JSON back (a client that cannot accept JSON gets 406). Those two attributes are content negotiation expressed as routing — the handler-mapping step itself checks them, which is why the failures happen before your code is ever called:"""),
                        code("map:health", "java",
                                "guide/presentation/HealthController.java — the smallest real mapping in the app: fixed path, GET only, no constraints",
                                "server/src/main/java/guide/presentation/HealthController.java", """
                                @GetMapping("/api/health")
                                HealthResponse health() {
                                    return new HealthResponse("ok", "springboot-developers-guide");
                                }"""),
                        code("map:produces-consumes", "java",
                                "guide/presentation/demo/HttpDemoController.java — the inspector's POST variant: consumes and produces declared at the mapping",
                                "server/src/main/java/guide/presentation/demo/HttpDemoController.java", """
                                @PostMapping(value = "/api/demo/http/inspect",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                HeadersExhibit.InspectReport post(@RequestBody(required = false) JsonNode body,
                                        HttpServletRequest request) {"""),
                        text("map:why-shortcuts", """
                                Why prefer annotations over any alternative at all? Because they put the route NEXT TO the code that runs — the HTTP surface of a controller reads at a glance top to bottom, and tools know where to look (springdoc reads these annotations to build the OpenAPI document in the api-documentation topic; mock slices like @WebMvcTest(RoutingDemoController.class) register exactly one controller's routes). The mapping is metadata about the method, attached to the method; that locality is the whole ergonomic argument, and it compounds in the next two sections: relative paths compose with a class-level base, and overlapping patterns resolve by declared specificity.""")),
                section("path-variables-query-patterns", "Path variables, query parameters, and the pattern dialect",
                        "The path names the resource; the query parameterizes the request. Inside a path pattern, {var}, {var:regex}, *, and {*rest} each mean something precise — and precisely ranked.",
                        text("var:division", """
                                The load-bearing division: PATH segments IDENTIFY the resource (/api/content/java-language names the thing), QUERY parameters PARAMETERIZE the request (?tasks=24&mode=virtual tunes how it is served). Path changes identity; query changes representation or behavior. That's why @PathVariable binds path segments and @RequestParam binds query strings — different syntax because different meaning. Both bind by name, and both are plain method parameters with no annotation ceremony beyond the marker. The jvm demo controller is the app's real example of query binding, with the optional-parameter spelling (name + required = false, absent means null) you will use constantly:"""),
                        code("var:request-param", "java",
                                "guide/presentation/demo/JvmDemoController.java — optional query parameters by name; absence arrives as null, validation lives in the use case",
                                "server/src/main/java/guide/presentation/demo/JvmDemoController.java", """
                                /** GET /api/demo/jvm/threads?tasks=24&mode=virtual → 200 run report or 400 envelope. */
                                @GetMapping("/api/demo/jvm/threads")
                                ThreadsExhibit.RunReport threads(@RequestParam(name = "tasks", required = false) String tasks,
                                        @RequestParam(name = "mode", required = false) String mode) {
                                    return unwrap(runThreadsDemo.execute(tasks, mode));
                                }"""),
                        text("var:dialect", """
                                The path-pattern dialect (Boot 4 parses every mapping with PathPatternParser — the old AntPathMatcher dialect is gone from this app's world) has exactly the moves below, and this app registers a live specimen for the interesting ones. {var} matches ONE whole segment and captures it (bound via @PathVariable). {var:regex} matches one segment only if the regex matches the WHOLE segment — /specimens/{id:\\d+} binds "42" and never "nope"; the constraint is why. A bare * matches one segment, uncaptured; inside a literal segment ? matches one character and * a run (notes-* catches notes-2026 and notes-x). {*var} — the modern catch-all — consumes ZERO OR MORE segments to the end and captures them WITH the leading slash ({*rest} binds "/a/b/c"); a trailing ** means the same swallow without capture, and a LEADING **/ lets a pattern match at any depth. What the dialect refuses matters too: ** in the middle of a pattern is a startup-time parse error ("place ** at the start or end"), matching is case-sensitive, and there is no trailing-slash tolerance — /api/content/ is a different path from /api/content (its trailing empty segment is why /api/content/* matches it and the literal does not).

                                Here are the explorer's own three specimen mappings — the constants are the registered patterns, verbatim from the controller:"""),
                        code("var:specimens", "java",
                                "guide/presentation/demo/RoutingDemoController.java — three real pattern routes: a regex variable, a plain variable, and a rest-of-path catch-all",
                                "server/src/main/java/guide/presentation/demo/RoutingDemoController.java", """
                                private static final String NUMERIC_ID_SPECIMEN = "/specimens/{id:\\\\d+}";
                                private static final String SECTION_SPECIMEN = "/{section}";
                                private static final String REST_SPECIMEN = "/specimens/{*rest}";
                                // …
                                @GetMapping(value = NUMERIC_ID_SPECIMEN, produces = MediaType.APPLICATION_JSON_VALUE)
                                RouteTableExhibit.SpecimenReport numericIdSpecimen(@PathVariable String id) {
                                // …
                                @GetMapping(value = SECTION_SPECIMEN, produces = MediaType.APPLICATION_JSON_VALUE)
                                RouteTableExhibit.SpecimenReport sectionSpecimen(@PathVariable String section) {
                                // …
                                @GetMapping(value = REST_SPECIMEN, produces = MediaType.APPLICATION_JSON_VALUE)
                                RouteTableExhibit.SpecimenReport restSpecimen(@PathVariable String rest) {"""),
                        code("var:wire", "http", "the three specimens probed on the wire — 42 binds the regex, 'nope' falls through to the catch-all", null, """
                                GET /api/demo/routing/specimens/42     → 200  { pattern: "/api/demo/routing/specimens/{id:\\d+}", captured: { id: "42" } }
                                GET /api/demo/routing/specimens/nope   → 200  { pattern: "/api/demo/routing/specimens/{*rest}",   captured: { rest: "/nope" } }
                                GET /api/demo/routing/specimens/a/b/c  → 200  { pattern: "/api/demo/routing/specimens/{*rest}",   captured: { rest: "/a/b/c" } }
                                GET /api/demo/routing/proof            → 200  { pattern: "/api/demo/routing/{section}",           captured: { section: "proof" } }"""),
                        text("var:choosing", """
                                Choosing where a value belongs is API design, and the rule is stability: identity in the path, tuning in the query. /api/content/{topicId} puts identity in the path because changing it names a different resource; the jvm demo's ?tasks=24 is a query because the same endpoint with different tuning is still the same request surface. Pagination, filtering, and sorting are query material (page, limit, sort — the api-design topic systematizes this); section slugs and ids are path material. When a segment must carry structure, prefer {var:regex} over validating inside the handler: the constraint participates in ROUTING, so a non-matching segment never reaches your code at all — it falls through to whatever pattern (or 404 path) is next, which you can watch live by probing /api/demo/routing/specimens/42 and /specimens/nope in the explorer's match panel and comparing the winners.""")),
                section("controllers-as-mounted-routers", "Controllers as mounted routers",
                        "A class-level @RequestMapping base path is Express's app.use('/api/x', router) — the controller is a router, mounted at a prefix, holding relative routes.",
                        text("mount:analogy", """
                                In the express guide you never registered routes on the app itself; you built routers and mounted them: const router = express.Router(); router.get('/:id', …); app.use('/api/content', router). Spring's equivalent is composition: put @RequestMapping("/api/demo/routing") on the CLASS and every method mapping becomes RELATIVE to it — at startup Spring combines class path + method path into one registered pattern, so @GetMapping("/table") on that class registers /api/demo/routing/table. Same benefit as Express: the controller's routes read relative to their own mount point, the prefix lives in exactly one place, and the mount can be re-parented by editing one line. This topic's demo controller is mounted exactly that way — and it is the app's first controller to do so deliberately, as a teaching specimen:"""),
                        code("mount:controller", "java",
                                "guide/presentation/demo/RoutingDemoController.java — the mounted router: class-level base, method-level relative paths, combined at startup",
                                "server/src/main/java/guide/presentation/demo/RoutingDemoController.java", """
                                @RestController
                                @RequestMapping(RoutingDemoController.BASE)
                                public class RoutingDemoController {

                                    /** The mount point: every mapping below is relative to this base. */
                                    public static final String BASE = "/api/demo/routing";
                                    // …
                                    @GetMapping(value = "/table", produces = MediaType.APPLICATION_JSON_VALUE)
                                    RouteTableExhibit.TableView table(@RequestParam(name = "prefix", required = false) String prefix) {
                                        return unwrap(listRoutes.execute(prefix));
                                    }"""),
                        text("mount:layout", """
                                WHERE controllers live matters as much as how they map. This app organizes presentation by FEATURE, not by layer: one thin controller per demo area (presentation/demo/JavaDemoController, HttpDemoController, RoutingDemoController…), each owning its whole vertical — mapping, extraction, one use case call, response shaping — with zero knowledge of other areas. The alternative, package-by-layer (all controllers in one package, all services in another), couples strangers: every new feature edits the same packages, and "what does the routing demo touch?" requires cross-package archaeology. Feature packaging makes the answer one directory. It also matches how you DELETE things: a topic's demo is one controller + one domain area + one config class, removable as a unit. The repo's shape mirrors this everywhere — domain/routing, data/routing, config/RoutingDemoConfig, presentation/demo/RoutingDemoController — four layers, one feature, four files that travel together.

                                The client plays the same trick in its own idiom, and its demo registry is the clearest echo: ids map to lazily-imported components exactly the way paths map to handlers — a mount table, not a switch statement:"""),
                        code("mount:registry", "ts",
                                "client/src/ui/demos/demo-registry.tsx — the client's mount table: ids to components, exactly one entry per demo",
                                "client/src/ui/demos/demo-registry.tsx", """
                                const RoutingExplorerDemo = lazy(() => import('./routing/explorer_demo'));
                                // …
                                export const demoRegistry: Readonly<
                                  Record<string, LazyExoticComponent<DemoComponentType>>
                                > = {
                                  // …
                                  'routing:explorer': RoutingExplorerDemo,
                                  // …
                                };"""),
                        text("mount:thin", """
                                Mounting changes none of the controller's other duties — it stays THIN by this app's contract: parse and validate input, call exactly one use case, unwrap the Result onto HTTP (the table mapping above is the entire method). Routing configuration belongs in the mapping annotations; business decisions never do. When a mounted router starts collecting cross-cutting needs (auth checks, request logging), the answer is not fatter controllers — it is the filter/interceptor layer, which is precisely the next topic's subject: routing picks WHO handles; filters and interceptors shape WHAT surrounds every handler regardless of who won.""")),
                section("matching-and-404", "Matching, specificity, and the 404",
                        "Overlapping patterns resolve by a precise specificity order — and the unrouted request has a more interesting story in Boot 4 than the old NoHandlerFoundException folklore.",
                        text("match:algorithm", """
                                What actually happens per request: the handler mapping filters the registry to patterns whose method matches and whose pattern matches the path — sometimes ZERO candidates (the 404 story), sometimes SEVERAL (GET /api/demo/routing/table matches both the exact literal and the {section} specimen). With several, Spring sorts them by specificity and dispatches the winner. The order is not registration order and not luck — it is PathPattern's comparator, and its rules are learnable to the number. A CATCH-ALL ({*var}, trailing **) always loses to any non-catch-all. Otherwise lower SCORE wins: each captured variable costs 1, each unbounded wildcard (* segment, or * inside a literal) costs 100 — so /api/content/{topicId} (score 1) outranks /api/content/* (score 100), and an exact literal (score 0) outranks both. Ties break by normalized length: more literal characters = more specific. That is the entire algorithm, and it is why exact > variable > wildcard > catch-all always holds:"""),
                        code("match:comparator", "java",
                                "guide/domain/routing/RoutePattern.java — the comparator, mirroring Spring's verified order: catch-all flag, then score, then length",
                                "server/src/main/java/guide/domain/routing/RoutePattern.java", """
                                public static final Comparator<RoutePattern> SPECIFICITY =
                                        Comparator.comparingInt((RoutePattern p) -> p.catchAll ? 1 : 0)
                                                .thenComparingInt(p -> p.score)
                                                .thenComparing(p -> -p.normalizedLength);"""),
                        text("match:live-proof", """
                                The explorer makes that live. Probe GET /api/demo/routing/table: the answer ranks the exact mapping first (score 0, no captures — maximally specific) with {section} behind it and a note for every candidate. Probe the digit specimen and watch {id:\\d+} (score 1) beat the catch-all {*rest} outright. One caution you can also verify there: specificity ranking does not license ambiguity. When two patterns tie on the ranking for the same request, Spring's behavior is not something to design against — distinct intent should produce distinctly-ranked patterns, and if you find yourself needing a tiebreak, the patterns are too close and one should go."""),
                        code("match:wire", "http", "the match endpoint's ranked answer for GET /api/demo/routing/table (abridged)", null, """
                                GET /api/demo/routing/match?path=/api/demo/routing/table&method=GET

                                { "verdict": "matched",
                                  "winner": { "pattern": "/api/demo/routing/table", "controller": "RoutingDemoController" },
                                  "candidates": [
                                    { "entry": { "pattern": "/api/demo/routing/table" }, "score": 0,
                                      "explanation": "rank 1 of 2 matching — specificity score 0, no captures — maximally specific" },
                                    { "entry": { "pattern": "/api/demo/routing/{section}" }, "score": 1,
                                      "explanation": "rank 2 of 2 matching — specificity score 1 (1 captured variable)" } ] }"""),
                        text("match:404-truth", """
                                Now the story everyone gets wrong: what happens when NOTHING matches. The old folklore says the DispatcherServlet throws NoHandlerFoundException. In a default Boot 4 app it does not, and this is verified, not documented folklore: Boot auto-registers a static-resource handler on /** — so an unrouted request still finds A handler. The resource handler finds no file, and throws NoResourceFoundException — an ErrorResponse whose own status is 404 — which Boot's default error handling renders as a 404 through the /error machinery (BasicErrorController's JSON). NoHandlerFoundException only arises when there is genuinely no handler at all — for example in a @WebMvcTest slice with no resource mapping (this repo's tests hit exactly that), or when you disable the resource mapping yourself. The wrong-method sibling behaves the same way: POST /api/health resolves to HttpRequestMethodNotSupportedException, an ErrorResponse carrying 405.

                                And here is this app's own history with that truth — a bug this topic flagged and the exception-handling topic fixed: our global advice installs a catch-all @ExceptionHandler(Exception.class) as its safety net, and for a while that net swallowed NoResourceFoundException and HttpRequestMethodNotSupportedException TOO — an unrouted path on this app rendered our 500 envelope instead of Boot's 404, a wrong method 500 instead of 405. The safety net was built for OUR exceptions; framework ErrorResponses fell into it because they are also exceptions. The fix (live now): the advice carries an explicit branch for the framework's ErrorResponse-typed misses that keeps their own status in OUR envelope — unrouted → 404 not_found, wrong method → 405 method_not_allowed. The routing lesson that motivated the bug stands regardless: know which exception your miss actually produces, because the default and your advice may disagree with each other:"""),
                        code("match:catchall-advice", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the safety net for genuinely unknown faults (framework ErrorResponses now have their own branch above it)",
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
                        code("match:wire-misses", "http", "verified on this app today — an unrouted path vs a ROUTED 404 with a typed envelope", null, """
                                GET /api/definitely/not/routed
                                → 404  {"error":{"code":"not_found","message":"No static resource api/definitely/not/routed.","requestId":"…","title":"Not Found"}}
                                  (NoResourceFoundException → the advice's ErrorResponse branch — its own status, our envelope)

                                GET /api/content/no-such-topic
                                → 404  {"error":{"code":"not_found","message":"no topic with id \\"no-such-topic\\"","requestId":"…"}}
                                  (a ROUTED miss: the handler ran and returned a typed not-found Result)"""),
                        text("match:typed-404", """
                                That second transcript is the distinction to internalize: there are TWO kinds of miss, and only one of them is routing's. An UNROUTED path never reached any handler — framework territory (Boot: 404 via the resource handler's exception). A ROUTED miss reached the handler with an unknown identifier — YOUR territory, and this app's convention is a typed failure from the use case, rendered by the same single advice into the 404 envelope with a request id. The use case returns a value; the edge maps it. Nothing is swallowed, and the 404 carries a message that names the missing thing:"""),
                        code("match:not-found", "java",
                                "guide/domain/content/GetTopicById.java — the routed 404 as a value: unknown id → Failed Result carrying not_found",
                                "server/src/main/java/guide/domain/content/GetTopicById.java", """
                                return repository.findTopicById(id)
                                        .map(topic -> Result.<TopicDetail>success(TopicDetail.of(topic)))
                                        .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                                                "no topic with id \\"" + id + "\\"", Map.of("topicId", id))));"""),
                        text("match:verdicts", """
                                The explorer encodes the whole taxonomy as its match verdicts: matched (winner + ranked candidates), method_not_allowed (some pattern matched the path, none allows the method — the 405 story), and no_match (nothing matched — the 404 story). Those three are also the complete decision table for any request's fate at the routing step, and the curl tour at the end of this topic lets you reproduce each branch from a terminal.""")),
                section("routing-at-scale", "Routing at scale",
                        "Dozens of controllers, hundreds of mappings — what holds up (annotation locality, per-slice advice), what tempts (base classes), and the functional RouterFunction alternative judged honestly.",
                        text("scale:what-holds", """
                                The declarative style scales on two legs. Locality: each controller is self-describing — its mount point, its routes, its produces/consumes — so understanding one area never requires cross-referencing a central route table (the registry exists, but as a derived view, like the explorer). Sliceability: because routes arrive as annotations, the test story (mock one controller's routes with @WebMvcTest), the documentation story (springdoc derives OpenAPI per controller), and the advice story all compose per-feature. This app's per-topic @Configuration wiring (each demo owns config/RoutingDemoConfig-style beans) is the same principle one layer down: nothing shared grows a god-file.

                                The temptation to resist is the REST base class: a generic CrudController<T> that inherited controllers specialize. It looks DRY and rots fast — inherited mappings are invisible in the subclass (surprise routes), springdoc documents the parent's shapes not your intent, method-security and validation annotations interact with inheritance in ways that surprise, and every "one more special case" forks the abstraction until it is a framework of its own. The composition answer is boring and better: a @ControllerAdvice per concern (not per inheritance tree — the exception topic builds this), a small helper for the repetitive parts, and explicit mappings even when three controllers rhyme. Duplication you can read beats inheritance you must simulate in your head."""),
                        text("scale:functional", """
                                Spring has a second, functional routing style — no annotations, no controller beans: a RouterFunction declares routes as lambdas in a @Bean method, and a handler function answers with a ServerResponse. It is a REAL alternative, not a toy, and this app carries exactly one live specimen so the comparison is against running code. Read it as the same route table, expressed as code-as-configuration — route GET this-path to this lambda, nothing reflected, nothing scanned:"""),
                        code("scale:routerfunction", "java",
                                "guide/config/RoutingDemoConfig.java — the functional specimen: a real registered route as a lambda bean",
                                "server/src/main/java/guide/config/RoutingDemoConfig.java", """
                                @Bean
                                RouterFunction<ServerResponse> routingFunctionalSpecimen() {
                                    return RouterFunctions.route()
                                            .GET("/api/demo/routing/functional/ping",
                                                    request -> ServerResponse.ok()
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .body(new FunctionalPing("RouterFunction",
                                                                    "a lambda handler — no annotation, no controller bean,"
                                                                            + " and invisible to the annotation registry")))
                                            .build();
                                }"""),
                        text("scale:comparison", """
                                The honest comparison. Declarative annotations win for almost everything: parameter binding (@PathVariable/@RequestParam/@RequestBody with validation — functional handlers parse the request themselves), OpenAPI generation, @WebMvcTest slices, team familiarity, and IDE navigation from route to code. Functional routing wins where code-as-config genuinely beats metadata: tiny embedded surfaces, routes computed at startup from configuration or a registry (loop and build), converters that must compose as functions, or when you want a routing DSL to read as one expression. It also has one quirk this app's explorer demonstrates live: RouterFunctions register in a DIFFERENT handler mapping than annotations, so /api/demo/routing/functional/ping answers 200 on the wire while being absent from the explorer's table — a real reminder that "the route table" is only ever per-mapping-technology. The verdict this guide follows: annotations for real endpoints, functional style when routes are BUILT rather than declared, and never a mixture inside one feature without a comment saying why.

                                Scale also eventually asks about versioning (/api/v2/…), prefix strategies, and route organization across teams — those are API-design decisions rather than routing mechanics, and the api-design topic owns them; the mechanics you need are already on the table."""),
                        code("scale:verdicts", "java",
                                "guide/domain/routing/RouteTableExhibit.java — the explainer's typed verdicts: the same three-branch fate every request meets at the routing step",
                                "server/src/main/java/guide/domain/routing/RouteTableExhibit.java", """
                                if (candidates.isEmpty()) {
                                    return new MatchReport(method, path, VERDICT_NO_MATCH, false,
                                            null, Map.of(), List.of(), routes.size(), nonMatched);
                                }
                                if (pathMatches.isEmpty()) {
                                    return new MatchReport(method, path, VERDICT_METHOD_NOT_ALLOWED, false,
                                            null, Map.of(), candidates, routes.size(), nonMatched);
                                }""")),
                section("terminal-companion", "The route table from a terminal",
                        "curl the explorer, rank a match, probe the specimens, meet the functional route, and reproduce both miss shapes yourself.",
                        text("term:tour", """
                                The explorer is a thin shell over two GET endpoints, which means everything it shows is reproducible with curl — and a few things (the miss shapes, the functional route, the raw registry) are EASIER to read raw. Start the server (docker compose up, or run GuideApplication), then take the tour. Watch for the ranked candidates in the match answers, the difference between the two 404-shaped probes at the end, and the functional ping that the table cannot see:"""),
                        code("term:curl-table", "bash", "the table: the whole registry, then narrowed by prefix", null, """
                                # Every registered annotation mapping, sorted:
                                curl -s 'localhost:8080/api/demo/routing/table' | head -40

                                # Only the routing demo's own routes (table, match, specimens):
                                curl -s 'localhost:8080/api/demo/routing/table?prefix=/api/demo/routing'"""),
                        code("term:curl-match", "bash", "the match probe: winners, rankings, and typed verdicts", null, """
                                # Exact beats variable — the ranked candidates explain why:
                                curl -s 'localhost:8080/api/demo/routing/match?path=/api/demo/routing/table&method=GET'

                                # Regex constraint vs catch-all, on a digit segment:
                                curl -s 'localhost:8080/api/demo/routing/match?path=/api/demo/routing/specimens/42&method=GET'

                                # The 405 story — path matches, method does not:
                                curl -s 'localhost:8080/api/demo/routing/match?path=/api/health&method=POST' | head -5

                                # The 404 story — nothing matches, typed verdict:
                                curl -s 'localhost:8080/api/demo/routing/match?path=/api/definitely/missing&method=GET'"""),
                        code("term:curl-specimens", "bash", "the live specimens and the functional route the table cannot see", null, """
                                # The three pattern specimens, dispatched for real:
                                curl -s localhost:8080/api/demo/routing/specimens/42
                                curl -s localhost:8080/api/demo/routing/specimens/nope
                                curl -s localhost:8080/api/demo/routing/proof

                                # The functional specimen — answers on the wire, absent from the table:
                                curl -s localhost:8080/api/demo/routing/functional/ping
                                curl -s 'localhost:8080/api/demo/routing/table?prefix=/api/demo/routing/functional'"""),
                        code("term:curl-misses", "bash", "the two miss shapes on the real wire — verify, don't trust prose", null, """
                                # Unrouted path: framework NoResourceFoundException → the advice's
                                # ErrorResponse branch → its own status in our envelope (404 not_found)
                                curl -i localhost:8080/api/definitely/not/routed

                                # Routed miss: handler ran, typed not_found → 404 envelope
                                curl -i localhost:8080/api/content/no-such-topic""")));
    }
}
