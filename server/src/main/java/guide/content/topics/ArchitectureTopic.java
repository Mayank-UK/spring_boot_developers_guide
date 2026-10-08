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
 * Topic 32 — Architecture. Group platform, order 3. The capstone of the
 * middle: everything topics 01–31 built, seen as ONE shape. The dependency
 * rule taught from this app's own body — a 500-file source tree whose rule
 * held — and ENFORCED by a mechanical test; the layer map computed from the
 * running app; the request-flow tracer assembling the layered story from
 * the app's own instrumentation (topic 06's aspect records, topic 08's
 * pipeline ring, measured timings); the client's mirror of the same rule.
 */
public final class ArchitectureTopic {

    private ArchitectureTopic() {
    }

    public static Topic topic() {
        return new Topic("architecture", "Architecture",
                "Everything this guide built so far, seen as one shape. The dependency rule — "
                        + "presentation → domain ← data — taught from this app's own 500-file body "
                        + "and enforced by a mechanical test that scans the source tree; the layer "
                        + "map computed from the running application; thin controllers, the "
                        + "composition root, and errors across the layers; the React client's "
                        + "mirror of the same rule; and a request-flow tracer that drives one real "
                        + "internal request and renders every layer it crossed.",
                TopicGroupId.PLATFORM, 3,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-architecture-at-all", "Why architecture at all",
                        "Not diagrams — the decisions that stay expensive to change.",
                        text("arch:why-complexity", """
                                THIRTY-ONE TOPICS AGO this codebase was one controller and a health check. Today it is roughly five hundred source files: a typed content registry, twenty-plus domain areas with ports and use cases, JDBC and JPA and Redis and Mongo adapters, GraphQL and gRPC and WebSocket surfaces, security chains, jobs, webhooks. Nobody held all of it in their head at any point — and that is why the architecture question arrives NOW: COMPLEXITY COMPOUNDS. Every feature is easier or harder to build depending on decisions made ten features ago, by someone who could not see this far. Architecture is the name for those decisions — the ones that are expensive to change later, and therefore worth choosing early.

                                The economics are asymmetric. A method extracted wrong costs an afternoon. A LAYER BOUNDARY drawn wrong costs a quarter: every change afterwards crosses it, every test mocks around it. And the worst case is not a badly drawn boundary — it is NO boundary. A big ball of mud is not an architectural style; it is an ABSENCE — the shape you get when every dependency decision is made locally, for today's convenience, with no rule forcing the question "which layer should own this?". Mud is not created by bad architects; it is created by the DEFAULT. Absent a rule, dependencies accrete in whatever direction the pull request was flowing that day.

                                So the working definition this topic uses: architecture = the rules about which code may depend on which code, plus the few places those rules are deliberately bent. Everything else — folder names, diagram boxes, hexagons and onions — is vocabulary for talking about those two things."""),
                        text("arch:why-cheap-rule", """
                                The rule this app chose is the cheapest one that still pays: DEPENDENCIES POINT INWARD, TOWARD THE DOMAIN. The domain — entities, ports (interfaces), use cases — knows nothing about HTTP, databases, or Spring. Presentation depends on the domain. Data depends on the domain. Neither knows about the other. That single sentence, held for thirty topics, is why this guide could add PostgreSQL (topic 17) under a fake-backed posts API (topic 11) WITHOUT TOUCHING ONE CONTROLLER; why GraphQL (topic 13) reused the same use cases as REST; why the scenario router can swap a fake for a live adapter at runtime for every demo.

                                None of that was luck, and none of it is sophistication — this app is LAYERS, the oldest diagram in the book, not hexagonal-with-cqrs-es. The lesson from its own body: a boring rule, held CONSISTENTLY and enforced MECHANICALLY, beats an elegant rule held by convention. How do you hold a rule across five hundred files? Not by reviewing harder — you write it as a test, and that test is this topic's centerpiece.""")),
                section("the-layer-map", "The layer map",
                        "The real package layout, computed from the running application — not drawn.",
                        text("arch:layers-own", """
                                THE MAP IS SIX PACKAGES, and each one OWNS something specific. guide.core is the shared kernel both outer layers may use: the Result type, the error taxonomy, the request-id filter, the two evidence rings. guide.domain is the inside: entities (Post, TopicDetail), ports (the interfaces), and use cases (one class per verb — GetPost, ListPosts, CreatePost); plus guide.domain.teaching for compilable exhibits where no real counterpart exists. guide.data holds the adapters — JDBC, JPA, Redis, Mongo, files, messaging — each answering a domain port. guide.presentation is the thin edge: controllers, the advice, interceptors, websocket config. guide.config is the COMPOSITION ROOT — every port-to-adapter wire is an explicit @Bean there. And guide.content is the registry island: the typed topic tree the client renders, which deliberately imports none of the app it documents.

                                The vocabulary matters more than the boxes. ENTITIES carry the data; PORTS are the interfaces the domain owns (the domain defines what it NEEDS, adapters provide it); USE CASES are the verbs — one public execute() per thing the app does; ADAPTERS translate a port to a technology; the COMPOSITION ROOT is the only place dependencies get CHOSEN. When this guide said "one class per verb" in topic 11 or "the port is the contract" in topic 17, this section is what those sentences pointed at.

                                A map that lives in a diagram rots; this one is COMPUTED. GET /api/demo/arch/map walks the running application's classpath at request time — the same reflection machinery topic 01 exhibited — counting classes per layer and inventorying every domain interface against its data implementations:"""),
                        code("arch:map-port", "java",
                                "server/src/main/java/guide/domain/arch/CodebaseMapSource.java — the domain states WHAT it wants to know about its own structure; the adapter decides HOW",
                                "server/src/main/java/guide/domain/arch/CodebaseMapSource.java", """
                                public interface CodebaseMapSource {

                                    CodebaseMap read();

                                    /** One architectural layer: its name, package root, class count, and per-area counts. */
                                    record LayerCount(String layer, String packageRoot, int classes, List<AreaCount> areas) {
                                    }

                                    /** Classes in one area (the package segment under the layer root). */
                                    record AreaCount(String area, int classes) {
                                    }

                                    /** One domain port interface: its name and the data-layer adapters + config bindings found for it. */
                                    record PortBinding(String port, List<String> adapters, List<String> bindings) {
                                    }

                                    /** The raw map: layers with counts, and the port→adapter inventory. */
                                    record CodebaseMap(List<LayerCount> layers, List<PortBinding> ports) {
                                    }
                                }
                                """),
                        text("arch:map-numbers", """
                                Run the demo's map panel and the numbers are the app's own census: domain is by far the largest layer (260-odd classes — the behavior lives there), presentation stays under sixty (the edge is thin because the edge is CHEAP). The port inventory lists every domain interface that has at least one adapter — PostRepository with its in-memory fake, its Pg adapter, and its scenario ROUTER, each wired by a named @Bean. When the next topic adds an area, the map grows the day the code lands — no diagram to remember to update. THAT is what "the code is the source of truth" means for architecture documentation.""")),
                section("the-dependency-rule", "The dependency rule — enforced",
                        "presentation → domain ← data. A JUnit test scans the source tree and fails the build if it is ever untrue.",
                        text("arch:rule-statement", """
                                THE RULE IN ONE LINE: source code dependencies point INWARD, toward the domain. Concretely, as this repo lives it: presentation may import domain, never data; data may import domain, never presentation; domain imports neither, and touches no web or persistence machinery at all; core and content import nothing outward. Like all style rules it is worthless as a sentiment — its entire value is that a MACHINE checks it on every build. This repo's machine is DependencyRuleTest, a plain JUnit test that walks src/main/java, parses each file's package and import lines, and asserts the rule mechanically.

                                Why a source-level import scan rather than bytecode analysis or ArchUnit? Honesty and cost. A constant-pool walk via ASM would also catch reflective edges, at the price of a dependency and code nobody can read in one sitting; this app has no reflection ACROSS layers (the tracer's classpath walk deliberately lives in data/), so import statements ARE the dependency graph. Scanning source keeps the test dependency-free and TEACHABLE, and choosing the simple mechanism while NAMING its limit — the tracer's map is the cross-check the day a reflective edge appears — is itself an architecture decision.

                                The spine of the test — two constants and the live assertions:"""),
                        code("arch:rule-test", "java",
                                "server/src/test/java/guide/arch/DependencyRuleTest.java — the banned machinery, the domain's free imports, and the rules that run against the real tree on every build",
                                "server/src/test/java/guide/arch/DependencyRuleTest.java", """
                                /** Imports the domain may never make — the machinery the rule exists to keep out. */
                                private static final List<String> DOMAIN_BANNED_PREFIXES = List.of(
                                        "org.springframework.web.",
                                        "org.springframework.jdbc.",
                                        "org.springframework.data.",
                                        "org.springframework.orm.",
                                        "org.springframework.boot.",
                                        "org.springframework.transaction.",
                                        "jakarta.persistence.",
                                        "jakarta.servlet.");

                                /** What the domain may import freely: the JDK and its own shared kernel. */
                                private static final List<String> DOMAIN_FREE_PREFIXES = List.of(
                                        "java.", "javax.", "guide.core.", "guide.domain.");

                                // …
                                @Test
                                void domainNeverImportsTheDataOrPresentationLayers() {
                                    assertNoViolations(violationsFor(scan()), "presentation → domain ← data");
                                }

                                @Test
                                void dataAndCoreAndContentNeverImportOutward() {
                                    assertNoViolations(violationsFor(scan()), "outward imports");
                                }

                                @Test
                                void theDomainTouchesNoWebOrPersistenceMachinery() {
                                    assertNoViolations(violationsFor(scan()), "banned machinery");
                                }
                                """),
                        text("arch:rule-sensitivity", """
                                A detector nobody tests can fail silently in BOTH directions — too lax (violations pass) or too strict (nobody trusts it). So the test proves itself twice. An ENABLED test feeds synthetic violating files through the same engine and asserts each rule reports its violation — sensitivity, checked on every build. And a deliberately @Disabled exhibit injects one presentation-imports-data file into the REAL scan; enable it locally and the suite goes red with the detector's own message — the fastest way to convince yourself green means clean, not asleep.

                                One parsing subtlety earned its keep: the topics quote code inside Java TEXT BLOCKS, and a naive regex happily reported those quoted imports as dependencies. The scanner strips text blocks and comments BEFORE matching — an import that exists only inside a quoted snippet is teaching, not coupling:"""),
                        code("arch:rule-scan", "java",
                                "server/src/test/java/guide/arch/DependencyRuleTest.java — comment and text-block stripping, then the two-regex parse",
                                "server/src/test/java/guide/arch/DependencyRuleTest.java", """
                                private static final Pattern PACKAGE_LINE = Pattern.compile(
                                        "^\\\\s*package\\\\s+([\\\\w.]+)\\\\s*;", Pattern.MULTILINE);
                                private static final Pattern IMPORT_LINE = Pattern.compile(
                                        "^\\\\s*import\\\\s+(?:static\\\\s+)?([\\\\w.]+)(?:\\\\.\\\\*)?\\\\s*;", Pattern.MULTILINE);

                                /**
                                 * Text blocks, block comments, and line comments are stripped BEFORE
                                 * matching — the topics quote code inside Java string literals, and an
                                 * import that exists only inside a quoted snippet is TEACHING, not a
                                 * dependency. (Order matters: text blocks first, so a snippet
                                 * containing comment-looking lines cannot confuse the comment strips.)
                                 */
                                private static final Pattern TEXT_BLOCK = Pattern.compile("\\"\\"\\"[\\\\s\\\\S]*?\\"\\"\\"");
                                private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\\\*[\\\\s\\\\S]*?\\\\*/");
                                private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\\\n]*");
                                """),
                        text("arch:rule-ledger", """
                                Now the honest part: EVERY real codebase bends the rule somewhere, and a rule with no bending procedure gets deleted in week three. This repo's bends live in a LEDGER — guide/domain/arch/ArchExemptions, a list of (file, import prefix, reason) entries that is the single source of truth for three consumers at once: the DependencyRuleTest (reality must equal the ledger, in BOTH directions — every bend is ledgered, and no STALE entry survives a cleanup), the /api/demo/arch/map endpoint (serves the same list), and this page. Adding a bend is a deliberate act: you write the entry and its reason, and it shows up in the API, the map panel, and the test's failure messages forever — an exemption that cannot state its reason is not an exemption, it is an unreviewed violation.

                                The counts at the time of writing: the domain carries 37 ledgered entries in five families — Jackson rendering annotations on records (topic 10's annotated-record exception, generalized), Bean Validation where the constraints ARE the contract, framework interfaces used AS ports (Spring's PasswordEncoder), teaching specimens where the imported type is the lesson (@PreAuthorize, @Cacheable, bcrypt), and infra-agnostic APIs (SLF4J, Jackson databind, Micrometer where topic 33 teaches it). Presentation reaches into data in exactly SEVEN places, every one a teaching exhibit reading demo-lifecycle state or recorded evidence that production code would never need through a port. Zero entries could legalize the banned machinery — that fence stands inside the ledger too. Here is the ledger's shape:"""),
                        code("arch:ledger", "java",
                                "server/src/main/java/guide/domain/arch/ArchExemptions.java — every bend, with its reason; the same list the map endpoint serves",
                                "server/src/main/java/guide/domain/arch/ArchExemptions.java", """
                                /**
                                 * One domain file plus the import prefix it is allowed to pull from
                                 * outside the JDK and guide.core/guide.domain. Files are paths under
                                 * src/main/java; prefixes are dotted Java package prefixes.
                                 */
                                public record DomainExemption(String file, String importPrefix, String reason) {
                                }

                                // …
                                public static List<DomainExemption> domainLedger() {
                                    return List.of(
                                            // -- serialization annotations on domain types (topic 10's
                                            //    annotated-record exception: @JsonValue/@JsonInclude are
                                            //    inert metadata; they change JSON rendering, never behavior)
                                            new DomainExemption("guide/domain/posts/PostStatus.java",
                                                    "com.fasterxml.jackson.annotation.",
                                                    "enum serialized by name — @JsonValue is rendering metadata, not behavior"),
                                            // …
                                            new TeachingExhibit("guide/presentation/graphql/PostsGraphqlController.java",
                                                    "guide.data.comments.",
                                                    "reads the fake repository's batchCallCount — the N+1 proof counter exists only on the fake"),
                                            // …
                                            }
                                """),
                        text("arch:rule-run", """
                                Run it the way the suite does — the whole guide's verification command exercises it on every build, or scope it: `mvn test -Dtest=DependencyRuleTest`. Watch what a violation costs you: the failure message names the file, the import, and the rule — the comment a reviewer would have written, arriving from the build in seconds instead of a review cycle later. This is the difference between CONVENTION and CONTRACT: conventions erode under deadline; contracts fail loudly and immediately. Thirty topics of "the rule held" in this repo is not a claim about discipline — it is the test being green the whole way down.""")),
                section("thin-controllers", "Thin controllers",
                        "Parse and validate the input, call ONE use case, map the Result. The 30-topic pattern, with its smell list.",
                        text("arch:thin-pattern", """
                                EVERY CONTROLLER IN THIS APP IS INTERCHANGEABLE AT A GLANCE, and that is the point. The anatomy, taught since topic 04 and unchanged since: PARSE the input, VALIDATE what parsing cannot judge, CALL exactly one use case, MAP the Result to an HTTP response — success value out, typed exception to the advice for failures. The guide's own content controller is the whole pattern in twenty lines, and it serves THE PAGE YOU ARE READING:"""),
                        code("arch:thin-content", "java",
                                "server/src/main/java/guide/presentation/ContentController.java — parse, one use case, map the Result; the guide serving itself",
                                "server/src/main/java/guide/presentation/ContentController.java", """
                                /** GET /api/content/{topicId} → 200 { version, ...topic } or 404 envelope */
                                @Operation(summary = "One topic's full section/block tree",
                                        operationId = "getTopicById",
                                        description = "Sections in order, each carrying its text, code, and demo blocks — "
                                                + "what TopicView renders.")
                                @GetMapping("/api/content/{topicId}")
                                TopicDetail detail(@PathVariable String topicId) {
                                    return unwrap(getTopicById.execute(topicId));
                                }

                                private static <T> T unwrap(Result<T> result) {
                                    return switch (result) {
                                        case Success<T> success -> success.value();
                                        case Failed<T> failed -> throw AppException.of(failed.failure());
                                    };
                                }
                                """),
                        text("arch:thin-why-smells", """
                                Why bother, when a controller COULD just do the work? Because everything that leaks into the handler becomes untestable without Spring, unreachABLE without HTTP, and unREUSABLE without copy-paste. The smell list, drawn from code this guide refused to write: BUSINESS LOGIC IN HANDLERS — an if-ladder about post statuses in a controller cannot be called from GraphQL, a job, or a test; A REPOSITORY IN A CONTROLLER — injecting the adapter couples the edge to storage and turns every contract test into an integration test; BRANCHING ON RESULT KINDS — when each handler re-decides what "not found" means, the API grows three shapes of 404; and CONSTRUCTING DEPENDENCIES — anything the handler builds itself is a wire the composition root did not choose, invisible to tests mocking the declared ones.

                                The payoff is measurable in the map: ~53 presentation classes carry 40+ endpoints across REST, GraphQL, gRPC and WebSocket, because each handler is a PARAGRAPH, not a module. The tracer's own controller below adds one move worth noticing: it marks its position on the calling request's pipeline trace (topic 08's pattern — the handler is the only code that knows it is executing) and passes that evidence IN to the use case:"""),
                        code("arch:thin-tracer", "java",
                                "server/src/main/java/guide/presentation/demo/ArchDemoController.java — the tracer's edge: parse target, mark the trace, one use case, unwrap",
                                "server/src/main/java/guide/presentation/demo/ArchDemoController.java", """
                                /** GET /api/demo/arch/trace?target=content|posts → 200 the layered story, or 400 for an unknown target. */
                                @Operation(summary = "Drive one real internal request and return its layered story",
                                        operationId = "traceRequestFlow",
                                        description = "Fires a genuine HTTP self-call while the app's own instrumentation "
                                                + "collects the evidence: the middleware pipeline's markers, the aspect-recorded "
                                                + "use-case invocation, and the measured timings.")
                                @GetMapping("/trace")
                                TraceRequestFlow.RequestFlowStory trace(@RequestParam(defaultValue = "content") String target,
                                        HttpServletRequest request) {
                                    PipelineTrace.mark(request, HANDLER_MARKER);
                                    return unwrap(traceRequestFlow.execute(target, requestId(request), PipelineTrace.markers(request)));
                                }
                                """),
                        text("arch:thin-rule", """
                                The discipline is already enforced for the worst smells by the dependency test — a repository in a controller IS a presentation→data import, and the build fails for it unless you write a ledger entry saying why. Thinness of the REST is judgment, enforced by review; thinness of the DEPENDENCIES is contract, enforced by the suite. You want both, and you get the second one free with the rule from the last section.""")),
                section("the-composition-root", "The composition root",
                        "config/* — the app's only place dependencies are chosen. Spring IS the container; the wires are explicit.",
                        text("arch:root-idea", """
                                SOMEWHERE, SOME CODE MUST DECIDE which adapter answers which port. This app's answer is ONE place: guide/config, one @Configuration class per topic area, every port-to-adapter relationship an explicit @Bean method. Topic 05 taught the mechanics — ApplicationContext, constructor injection, @Bean vs stereotypes; the architectural claim on top of them: CONCENTRATED WIRING IS A FEATURE. When every wire is a visible line, "which implementation serves this port?" is a grep; when wiring is scattered (@Component on classes, @Autowired by type), the answer is emergent and the app has no one place where its shape can be seen or changed.

                                The payoff is not tidiness — it is SUBSTITUTION at runtime. The posts substrate is the standing proof. One @Primary bean decides that the exposed PostRepository is a SCENARIO ROUTER over the in-memory fake and the PostgreSQL adapter; the ScenarioStore flips areas between fake and live at request time; NOTHING ABOVE THE PORT CHANGED when PostgreSQL arrived — not one controller, not one use case, not the GraphQL schema:"""),
                        code("arch:root-router", "java",
                                "server/src/main/java/guide/config/PostsDemoConfig.java — the port bean is a router; fake↔live flips at runtime through the ScenarioStore",
                                "server/src/main/java/guide/config/PostsDemoConfig.java", """
                                /** The exposed port: the router (fake by default, live on scenario flip). @Primary wins every by-type injection. */
                                @Primary
                                @Bean
                                PostRepository postRepository(ScenarioStore scenarioStore) {
                                    // Marks this area in the store's per-mode registry: fake data by
                                    // default, live at call time once ScenarioStore flips it (dataModeFor("api")).
                                    scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.FAKE);
                                    return new ScenarioRoutingPostRepository(scenarioStore, inMemoryPostRepository(),
                                            schemaMigrator::getObject, liveAdapter::getObject);
                                }

                                /** The fake adapter of record — deterministic seed, and the handle tests reset through. */
                                @Bean
                                InMemoryPostRepository inMemoryPostRepository() {
                                    return new InMemoryPostRepository();
                                }

                                /** The live adapter, @Lazy for the same reason: no DataSource touched until a live call. */
                                @Lazy
                                @Bean
                                PgPostRepository pgPostRepository(JdbcTemplate jdbcTemplate) {
                                    return new PgPostRepository(jdbcTemplate);
                                }
                                """),
                        text("arch:root-own", """
                                The arch topic's own config is the same pattern in miniature, and it is ALSO the tracer's design made visible: four ports (the internal HTTP probe, the instrumentation reader, the classpath map source — each an interface in domain/arch), their adapters in data/arch, and the use cases over them. Read the bean methods as a SENTENCE about the demo's shape: the traced use case sits between the orchestrator and the probe port specifically so the aspect's record lands in the ring before the orchestrator reads it back — even the ORDER of the wiring is an architecture decision, written where wiring lives:"""),
                        code("arch:root-arch", "java",
                                "server/src/main/java/guide/config/ArchDemoConfig.java — the topic's own composition root: ports to adapters to use cases, all explicit",
                                "server/src/main/java/guide/config/ArchDemoConfig.java", """
                                /** Port two: the two evidence rings, mapped into domain records. */
                                @Bean
                                FlowInstrumentation flowInstrumentation(InvocationLog invocationLog, PipelineTraceLog pipelineTraceLog) {
                                    return new ArchInstrumentationAdapter(invocationLog, pipelineTraceLog);
                                }

                                /** Port three: the computed layer map from the running classpath. */
                                @Bean
                                CodebaseMapSource codebaseMapSource() {
                                    return new ClasspathMapAdapter();
                                }

                                /** The traced use case the story exhibits — the aspect records this bean's calls. */
                                @Bean
                                ProbeInternalApi probeInternalApi(InternalProbe internalProbe) {
                                    return new ProbeInternalApi(internalProbe);
                                }

                                /** The tracer's orchestrator: probe, then read the evidence back. */
                                @Bean
                                TraceRequestFlow traceRequestFlow(ProbeInternalApi probeInternalApi,
                                        FlowInstrumentation flowInstrumentation) {
                                    return new TraceRequestFlow(probeInternalApi, flowInstrumentation);
                                }
                                """),
                        text("arch:root-spring", """
                                Notice what Spring is doing here — the least-understood part of the pattern: Spring IS the composition root's engine, and nothing else. The domain does not know it exists (the ledger would catch an import); the config classes are the only place framework and application code MEET. That is why topic 05's container demo could build the same graph by hand — the wires are plain constructors, and DI as an ARCHITECTURE idea (invert who chooses dependencies) is separable from DI as a LIBRARY. The library keeps the root cheap to maintain; the idea makes the ports real.""")),
                section("errors-across-the-layers", "Errors across the layers",
                        "Result at the seams, typed exceptions at the edge, one envelope on the wire.",
                        text("arch:errors-journey", """
                                A FAILURE BORN IN A USE CASE crosses three boundaries before a human sees it, and each crossing has its own currency. INSIDE the domain and at its ports, failures are VALUES: a use case returns Result.failed(Failure.of(kind, message)) instead of throwing, so expected failures cannot skip a frame. ACROSS the HTTP edge, the thin controller flips the currency: a Failed Result becomes a TYPED exception handed to the advice — throws are for the process boundary, where unwinding IS the right semantics. ON THE WIRE, the advice renders one envelope for every kind, with the request id. Topic 30 built the taxonomy's half; this section closes the loop: the CURRENCY CHANGES AT THE SEAMS, and only at the seams.

                                The Result type is the load-bearing contract (its javadoc says exactly this):"""),
                        code("arch:errors-result", "java",
                                "server/src/main/java/guide/core/result/Result.java — failures as values inside the layers; the edge decides what they mean on the wire",
                                "server/src/main/java/guide/core/result/Result.java", """
                                /**
                                 * The Result type used by every use case. Domain and data layers signal
                                 * failures as VALUES (never by throwing), so control flow stays explicit and
                                 * the presentation edge decides how failures map onto HTTP (see
                                 * core/errors/AppException — the bridge from a Failed Result to the typed
                                 * exception the handler advice renders).
                                 *
                                 * Success may carry warnings: non-fatal notes (degradation, fallbacks) that
                                 * travel WITH the value instead of aborting the call.
                                 */
                                public sealed interface Result<T> permits Success, Failed {
                                """),
                        text("arch:errors-seams", """
                                WHY TWO CURRENCIES? Because they answer different questions. A Result forces the IMMEDIATE caller to acknowledge the failure — the compiler will not let a use case's caller forget the case, which is what you want one layer in, where "not found" is routine. An exception unwinds to whoever CATCHES it — the right tool at the process boundary, where the advice is the single catcher. Mixing them up is expensive both ways: throwing from a use case makes every caller a try-catch minefield; returning Results from controllers just moves the switch someone still has to write. The tracer's traced use case is a one-screen specimen — even its UNEXPECTED path (the probe cannot reach the server) returns a Failed value, and the aspect still files its record with outcome ok because the call itself returned:"""),
                        code("arch:errors-probe", "java",
                                "server/src/main/java/guide/domain/arch/ProbeInternalApi.java — values, never throws, even for the unexpected; the edge does the mapping",
                                "server/src/main/java/guide/domain/arch/ProbeInternalApi.java", """
                                /** Drives the target's path over real HTTP; Result, never a throw. */
                                public Result<InternalProbe.InternalCall> execute(ProbeTarget target) {
                                    try {
                                        return Result.success(probe.get(target.path()));
                                    } catch (RuntimeException unreachable) {
                                        return Result.failed(Failure.of(FailureKind.UNAVAILABLE,
                                                "the internal probe could not reach " + target.path() + ": "
                                                        + unreachable.getMessage(),
                                                Map.of("target", target.id(), "cause", unreachable.getClass().getSimpleName())));
                                    }
                                }
                                """),
                        text("arch:errors-crossquote", """
                                The last leg — failure kind → HTTP status → envelope — is topic 30's ground (the eight-kind taxonomy, ProblemDetail, the request id in every envelope); architecture's contribution is that the MAPPING LIVES IN EXACTLY ONE PLACE. A new kind is born with its status and envelope shape; no controller, use case, or adapter decides what "conflict" means on the wire. Every error response you meet in this app is the same envelope — which is precisely why the client can have ONE Result for all of them, next section.""")),
                section("the-clients-mirror", "The client's mirror",
                        "core/domain/data/ui in the React app — the same dependency rule, both sides of the wire.",
                        text("arch:mirror-shape", """
                                THE REACT CLIENT IS NOT AN AFTERThought with a rule of its own — it is the same architecture, mirrored. client/src/core holds the container, the guarded fetch pipeline, the error taxonomy, and Result; client/src/domain holds use cases and port interfaces; client/src/data holds the repositories that call the server; client/src/ui renders. The dependency arrows point the same INWARD direction: ui → domain ← data, with core the shared kernel both may use. One diagram in prose covers both ends of the wire: the SERVER reads the request at its edge, maps it through use cases and adapters to data; the CLIENT mirrors it — ui event → use case → repository → HTTP → the server's edge. The contract between them is deliberately narrow: JSON in known shapes, ONE error envelope, correlation ids echoed everywhere.

                                Why does full-stack consistency MATTER, rather than "React does React its way"? Maintenance symmetry. When a failure crosses the wire, a developer debugs ONE journey, not two vocabularies: the client's Result.failed carries a Failure with the same kind-code-status-requestId fields the server's envelope rendered — you can follow a request id from a browser error panel to a server log line without translation. And the same rule can be tested: the client's layers are directories with import conventions, and nothing stops a spec from asserting them the way DependencyRuleTest asserts the server's. The mirrored Result is the clearest specimen — compare it to the Java one two sections ago:"""),
                        code("arch:mirror-result", "ts",
                                "client/src/core/result.ts — the client's Result: same discriminated shape, ok instead of Success/Failed",
                                "client/src/core/result.ts", """
                                /**
                                 * Result<T> — the domain return type for anything that can fail in an
                                 * expected, recoverable way. Mirrors the react/express guides' `Result<T>`:
                                 * a discriminated union on `ok` so switch/narrowing works ergonomically in
                                 * TypeScript.
                                 *
                                 * - `Success` carries a value and an *optional* `warning` (a non-blocking
                                 *   Failure, e.g. "showed stale cache because the network failed").
                                 * - `Failed` carries the blocking Failure (core/error-handling/failures.ts —
                                 *   the typed taxonomy with kind, severity, code, status, and requestId).
                                 *
                                 * Repositories guarantee a Result for EVERY outcome (they never throw); views
                                 * and use cases branch on `ok` alone.
                                 */
                                export type Result<T> = Success<T> | Failed;
                                """),
                        text("arch:mirror-demo", """
                                Even this topic's demo obeys the mirror: the tracer widget lives in ui/demos/arch/, resolves its dependencies through the container, fetches through the SAME guarded pipeline every data source uses (no bespoke fetch), runtime-validates the wire shape like every demo before it, and renders through the shared design system. The widget is PRESENTATION ONLY — it teaches nothing about React on purpose, because the server carries the teaching and the client carries the rendering; that division is itself the family's architecture decision, inherited whole from the express guide:"""),
                        code("arch:mirror-widget", "ts",
                                "client/src/ui/demos/arch/tracer_demo.tsx — the tracer's client half: guarded pipeline, runtime wire validation, no bespoke fetch",
                                "client/src/ui/demos/arch/tracer_demo.tsx", """
                                const load = useCallback(
                                  async (chosen: 'content' | 'posts') => {
                                    setState({ kind: 'loading' });
                                    const result = await safeRepositoryCall(async () => {
                                      const trace = await guardedGetJson(config, `/demo/arch/trace?target=${chosen}`);
                                      const map = await guardedGetJson(config, '/demo/arch/map');
                                      return {
                                        trace: parseDto(trace.body, trace.status, parseTraceBody),
                                        map: parseDto(map.body, map.status, parseMapBody),
                                      };
                                    });
                                    setState(
                                      result.ok
                                        ? { kind: 'ready', trace: result.value.trace, map: result.value.map }
                                        : { kind: 'error', failure: result.failure },
                                    );
                                  },
                                  [config],
                                );
                                """)),
                section("request-flow-tracer", "The request-flow tracer",
                        "One real internal request, driven while the app's own instrumentation collects the evidence — the layered story, rendered live.",
                        text("arch:tracer-how", """
                                EVERYTHING THIS TOPIC CLAIMED so far is checkable at runtime, so the topic ends by CHECKING it. The tracer drives one REAL internal request — a genuine HTTP self-call through the servlet stack, not a method call — and while it runs, the app's own instrumentation (already in the tree since topics 06 and 08) collects the evidence: the middleware pipeline's bounded ring files the request's full marker sequence when its outermost filter unwinds; the InvocationTraceAspect records the traced use-case invocation with real timings and the correlation id from the MDC; the probe measures the round trip from outside the process. Then the story is assembled — filter(request-id) → interceptor(preHandle) → controller (thin) → use case (aspect-recorded, durationNanos) → adapter → Result → payload-or-envelope → postHandle/afterCompletion — with every step citing WHERE its evidence came from.

                                The assembly is honest about its limits, by design: the inner trace is matched by CORRELATION ID (the probe captures the x-request-id the inner request echoed; the completed trace filed under the same id is provably the one this probe caused), and when nothing matches — concurrent traffic, or filters not registered in a slice — the story SAYS so instead of guessing, while the timings stay measured. Two requests means two ids, and the response shows both: the outer one you made, the inner one it drove, nested inside the outer's frame — the clearest one-picture summary of what layers buy you.

                                How to read the demo panel: the steps render top-to-bottom in execution order — filter and interceptor stages carry the ring's markers as evidence, the highlighted controller/use-case/adapter/result block is the HANDLER GAP (the silence between preHandle and postHandle, where the architecture actually lives), each citing its source; below it, the aspect records with real nanosecond timings, and the map panel with the census the layer-map section described."""),
                        demo("arch:tracer-demo", "arch:tracer", "Request-flow tracer",
                                "Drive one real internal request (content or posts) and watch every layer it crossed — middleware markers, the aspect-recorded use case, measured timings — beside the computed layer map and the exemption ledger."),
                        text("arch:tracer-sources", """
                                Three sources, all real, all named on every step. Topic 08's PipelineTraceLog — a bounded ring of COMPLETED traces, filed by the outermost middleware filter's finally block. Topic 06's InvocationLog — the aspect-fileed records (method, capped args, outcome, durationNanos, requestId); the tracer resets it before each run, the aop demos' discipline, so the visible tail is exactly this probe's evidence. And the probe's own wall clock. No number on that panel was invented, estimated, or cached — the snippet checker's claim for code, extended to a running system: the artifact is the source of truth.""")),
                section("terminal-companion", "Terminal companion",
                        "The curl tour: trace both targets, read the map, grep the ledger, run the enforcement.",
                        text("arch:terminal", """
                                THE SAME ARTIFACTS, no browser required — and the terminal shows what the panel abstracts: the raw marker sequence, the raw timings object, the census as plain numbers. Start the server (docker compose up, or run GuideApplication) on any port — the tracer derives the self-call's base URI from the incoming request, so nothing here is port-hardcoded beyond the conventional 8080. Take the tour; the last command is the whole topic in one line:"""),
                        code("arch:term-trace", "bash", "one real internal request — the layered story, then just its timings", null, """
                                # the whole layered story, step by step, evidence citations included:
                                curl -s 'localhost:8080/api/demo/arch/trace?target=content' \\
                                  | python3 -c 'import json,sys; print([s["label"] for s in json.load(sys.stdin)["steps"]])'

                                # the other target — the posts substrate, fake or live by scenario:
                                curl -s 'localhost:8080/api/demo/arch/trace?target=posts' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["timings"])'

                                # both correlation ids — two requests, one nested inside the other:
                                curl -s 'localhost:8080/api/demo/arch/trace?target=content' \\
                                  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["outer"]["requestId"], d["inner"]["requestId"])'"""),
                        code("arch:term-map", "bash", "the computed census — layers, the post port's adapters and binding", null, """
                                # class counts per layer (assert ranges, never totals — the tree grows):
                                curl -s localhost:8080/api/demo/arch/map \\
                                  | python3 -c 'import json,sys; print([(l["layer"], l["classes"]) for l in json.load(sys.stdin)["layers"]])'

                                # the taught port, its three adapters, and its single composition-root wire:
                                curl -s localhost:8080/api/demo/arch/map \\
                                  | python3 -c 'import json,sys; print([p for p in json.load(sys.stdin)["ports"] if p["port"].endswith("PostRepository")])'

                                # the exemption ledger — every bend, with reasons; watch for staleness:
                                curl -s localhost:8080/api/demo/arch/map \\
                                  | python3 -c 'import json,sys; print([e["file"] for e in json.load(sys.stdin)["teachingExhibits"]])'
                                curl -s localhost:8080/api/demo/arch/map \\
                                  | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["domainExemptions"]))'"""),
                        code("arch:term-enforce", "bash", "the rule itself, on your working copy — green means the tree still points inward", null, """
                                # the dependency rule, enforced (add -Dtest=DependencyRuleTest to scope it):
                                docker run --rm -v "$PWD":/repo -w /repo/server \\
                                  -v sbdg-m2:/root/.m2 maven:3.9-eclipse-temurin-25 mvn test

                                # want to watch the detector fire? Enable the @Disabled exhibit test in
                                # guide/arch/DependencyRuleTest.java and run it again — red, on purpose."""),
                        text("arch:terminal-read", """
                                What to notice in the output: the trace's steps array is the layered story in execution order — filter markers first, the handler block in the middle (its labels cite the controller source, the aspect record, the port inventory), the unwinding markers last. The timings carry the round trip, the aspect-recorded use-case duration, and their honest difference — never an exact repeated number, because they are measured per request. The port entry for PostRepository is the composition root made readable — three adapters, one @Bean. And the final command is the topic compressed: a five-hundred-file tree whose architecture is not a diagram but a green test.""")));
    }
}
