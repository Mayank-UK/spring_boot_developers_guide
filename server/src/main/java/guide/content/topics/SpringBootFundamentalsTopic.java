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
 * Topic 04 — Spring Boot Fundamentals. The first Spring-specific topic: what
 * Boot actually does to a Spring application — starters and the BOM, the
 * autoconfiguration machinery, the pipeline a request travels, and the
 * opinions you inherit and may override. Every snippet is quoted from this
 * repo's real source (SnippetSourcesTest verifies each quote mechanically);
 * the topic's live demo, spring:app-info, is mounted inline in the controller
 * section and reports the RUNNING context's own boot story.
 */
public final class SpringBootFundamentalsTopic {

    private SpringBootFundamentalsTopic() {
    }

    public static Topic topic() {
        return new Topic("spring-boot-fundamentals", "Spring Boot Fundamentals",
                "What Spring Boot actually does to a Spring app: starters and the BOM, "
                        + "autoconfiguration as conditional factories, the pipeline every request travels, "
                        + "and the opinions you inherit — and how to override each one.",
                TopicGroupId.FUNDAMENTALS, 4,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("setting-up-a-project", "Setting up a project",
                        "start.spring.io, starter dependencies, and the parent BOM — what a Boot project is, and why ours is six dependencies with zero version numbers.",
                        text("setup:initializer", """
                                Concept first: a Spring Boot project is three ordinary things — a build file (Maven pom.xml here), one application class with a main method, and a configuration file (application.yml). The fastest way to assemble those three is start.spring.io — Initializr, Boot's project generator: pick a build tool, a Java version, and "dependencies" (the labeled skills: Web, Actuator, Validation…), and it emits a runnable skeleton — parent-anchored pom, application class, empty application.yml, a test class.

                                What are those "dependencies", then? Not single libraries but starters: named, curated dependency sets. spring-boot-starter-web is not a jar of web code; it is a small pom that transitively pulls in the whole web stack (exactly what, in a moment). This repo was born that way — Initializr with Web, Validation, Actuator — then grown."""),
                        code("setup:parent", "xml",
                                "server/pom.xml — the parent anchor and our own coordinates: no Spring version numbers anywhere in our half",
                                "server/pom.xml", """
                                <parent>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-parent</artifactId>
                                    <version>4.1.1</version>
                                    <relativePath/>
                                </parent>

                                <groupId>guide</groupId>
                                <artifactId>server</artifactId>
                                <version>0.0.1-SNAPSHOT</version>"""),
                        text("setup:bom", """
                                That parent is the load-bearing line: spring-boot-starter-parent imports the real authority, the spring-boot-dependencies BOM ("bill of materials") — a curated list of a few hundred libraries with ONE version each, tested together by the Boot team: Spring Framework, Jackson, Tomcat, the logging stack. Because the BOM manages versions, your dependency list carries none — look again at the quote above: our half contributes coordinates, never versions.

                                Dependency management, not dependency injection: the BOM puts nothing on your classpath — it fixes versions for what you name. When you must move off the curated version (a critical fix, a regression), you override the BOM's property for that library in your pom's properties block — explicit, visible in review, yours to maintain. The contract: Boot picks defaults so you don't argue about them, and names the one place where disagreeing is allowed."""),
                        code("setup:starters", "xml",
                                "server/pom.xml — the whole runtime dependency list: capabilities, not versions",
                                "server/pom.xml", """
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-starter-web</artifactId>
                                    </dependency>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-starter-actuator</artifactId>
                                    </dependency>
                                    <!-- … validation, devtools, and test-only artifacts elided … -->
                                </dependencies>"""),
                        text("setup:pulled", """
                                So what does a starter actually pull in? Run mvn dependency:tree and read it — the habit answers more "where did this class come from" questions than documentation. spring-boot-starter-web resolves to the webmvc stack (Spring's servlet web framework), the embedded-Tomcat artifacts (a real servlet container as libraries, not an installed server), the JSON stack (Jackson, which serializes every response body in this guide), and the core starter with framework plus default logging. 

                                The rest of our list: validation (Bean Validation annotations on request inputs), actuator (health-and-ops endpoints, later in this topic), devtools — development-only, runtime-scoped and optional so it never ships — and two test artifacts, including spring-boot-webmvc-test (Boot 4 modularization: test slices live in per-technology jars, not one autoconfigure monolith). Notice what is absent: no database starter, no ORM, no caching.""")),
                section("application-and-autoconfiguration", "The application class and autoconfiguration",
                        "@SpringBootApplication is three annotations in one, and autoconfiguration is a factory pattern guarded by conditions — here is the machinery, and how to read its report.",
                        code("auto:application", "java",
                                "server/src/main/java/guide/GuideApplication.java — the entire entry point, verbatim",
                                "server/src/main/java/guide/GuideApplication.java", """
                                package guide;

                                import org.springframework.boot.SpringApplication;
                                import org.springframework.boot.autoconfigure.SpringBootApplication;

                                @SpringBootApplication
                                public class GuideApplication {

                                    public static void main(String[] args) {
                                        SpringApplication.run(GuideApplication.class, args);
                                    }
                                }"""),
                        text("auto:annotation", """
                                Twelve lines, and the middle two carry the whole framework. @SpringBootApplication is a meta-annotation stacking three others. It IS @Configuration: this class may declare @Bean methods and is itself a bean definition source. It IS @ComponentScan, with a default you must know: scan the declaring class's package and everything under it. That default is why this repo's root package is guide and every layer (core, content, domain, data, config, presentation) sits beneath it — one annotation finds everything. It IS @EnableAutoConfiguration — the switch that turns on the machinery in the next paragraph.

                                SpringApplication.run() then runs the startup sequence: assemble an ApplicationContext (topic 05's IoC container), component-scan your classes, evaluate every autoconfiguration candidate, refresh the context (singletons instantiate here), start the embedded server, publish ready. main() blocks inside run() for the life of the application."""),
                        text("auto:factories", """
                                Autoconfiguration is the piece developers treat as magic, so here is the demystified mechanism: a factory pattern guarded by conditions, evaluated once, at startup. @EnableAutoConfiguration reads a fixed manifest shipped inside Boot's jars — META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports — listing candidate configuration classes (a few hundred across the starters you pulled). Each candidate is an ordinary @Configuration that would define beans — a DataSource, a web server, an ObjectMapper — but every bean is wrapped in @Conditional guards: @ConditionalOnClass (only if some library is on the classpath), @ConditionalOnMissingBean (only if YOU have not already defined one), @ConditionalOnProperty (only if a config flag says so).

                                Two properties of that design matter more than the annotations. First, the guards answer "is this applicable?" before instantiating anything — and conditions like @ConditionalOnClass are evaluated by reading compiled class-file metadata with an ASM-based MetadataReader, WITHOUT loading the classes: absent libraries cannot crash the evaluation. Second, your own beans win: @ConditionalOnMissingBean backs off the moment you define the same type — a fallback for your code, not a rival. Every candidate either matched (its beans exist) or did not (recorded with a reason), and Boot keeps the scorecard — run with --debug to print the condition evaluation report on startup:"""),
                        code("auto:report", "bash", "the condition evaluation report — which factories matched, which backed off, and why", null, """
                                java -jar target/server-0.0.1-SNAPSHOT.jar --debug
                                # …
                                CONDITIONS EVALUATION REPORT
                                #############################

                                Positive matches:
                                ------------------
                                   JacksonAutoConfiguration matched:
                                      - @ConditionalOnClass found required class 'tools.jackson.databind.ObjectMapper'

                                Negative matches:
                                ------------------
                                   DataSourceAutoConfiguration:
                                      Did not match:
                                         - @ConditionalOnClass did not find required class 'jakarta.sql.DataSource'"""),
                        text("auto:your-own", """
                                Read that report once and autoconfiguration stops being folklore: you SEE that JacksonAutoConfiguration matched (the web starter put Jackson on the classpath, so the ObjectMapper serializing our responses is a framework bean), and that DataSourceAutoConfiguration did not (no database library present). The report is also the debugging tool for "why is my bean not being created" — usually a negative match with a reason, printed.

                                Your own configuration joins the same context as a first-class citizen — that is the composition root in config/. GuideConfig below is this repo's: plain @Configuration, explicit @Bean methods, constructor-made objects. Where it defines a bean an autoconfiguration also offers, the conditional backs off; where it defines types the framework never heard of (our use cases), nothing conflicts. Note scenarioStore reading raw properties through Spring's Environment — a preview of the demo adapter later in this topic."""),
                        code("auto:guide-config", "java",
                                "guide/config/GuideConfig.java — your configuration beside the autoconfigured: explicit beans, one Spring type at the seam",
                                "server/src/main/java/guide/config/GuideConfig.java", """
                                @Configuration
                                public class GuideConfig {

                                    @Bean
                                    ContentRegistry contentRegistry() {
                                        return new ContentRegistry(ContentTopics.all());
                                    }

                                    // … more beans: the port→adapter and use-case wiring …

                                    @Bean
                                    ScenarioStore scenarioStore(Environment environment) {
                                        return new ScenarioStore(ScenarioState.fromEnvironment(
                                                environment.getProperty("SCENARIO_DATA_MODE"),
                                                environment.getProperty("SCENARIO_INJECT_FAILURE")));
                                    }""")),
                section("anatomy-of-a-controller", "Anatomy of a controller",
                        "@RestController, @GetMapping, records in and Jackson out — plus the live demo: the running context describing itself.",
                        code("ctrl:health", "java",
                                "guide/presentation/HealthController.java — the smallest complete controller in this repo, verbatim",
                                "server/src/main/java/guide/presentation/HealthController.java", """
                                @RestController
                                public class HealthController {

                                    /** Immutable response body — Jackson serializes record components in declaration order. */
                                    record HealthResponse(String status, String service) {
                                    }

                                    @GetMapping("/api/health")
                                    HealthResponse health() {
                                        return new HealthResponse("ok", "springboot-developers-guide");
                                    }
                                }"""),
                        text("ctrl:mapping", """
                                Everything a controller is, in one small class. @RestController is itself two annotations: @Controller (this class may handle HTTP — component-scanned, so it becomes a bean) and @ResponseBody (return values ARE the response body, not view names for template rendering). @GetMapping("/api/health") registers the mapping pair method-plus-path. At startup, Spring's RequestMappingHandlerMapping walks every bean, collects these annotations, and builds the route table: path, method, and a reflective handle to the method. When a request arrives, "routing" is a map lookup on an already-built table — registration happened once, not per request.

                                The method returns a record. There is no serialization code to write: @ResponseBody hands the return value to Spring's message converters, and for a JSON response that means Jackson, which (via reflection over the record's components — topic 01's machinery) turns HealthResponse into {"status":"ok","service":"springboot-developers-guide"}. Records are ideal bodies: immutable, and their component order IS the JSON field order, which keeps golden tests stable. In Boot 4 the engine is Jackson 3 — new tools.jackson databinding under the hood, familiar com.fasterxml annotations on top; you write nothing different."""),
                        text("ctrl:thin", """
                                The grown-up controllers in this repo add one discipline: they are THIN. Parse the input, call exactly one use case, map the Result onto HTTP — no business logic. JavaDemoController (topic 01's) shows the pattern — the unwrap helper turns a Failed Result into the typed exception the global advice renders. The same helper reappears in the demo controller below — copy-paste by design, three lines each.

                                The demo: this topic ships with an endpoint that introspects the very machinery you are reading about. GET /api/demo/spring/app-info returns the RUNNING application's self-description: application name and active profiles from the Environment, Java version and vendor, when the app reported ready, how many bean definitions the context holds, how many @Configuration classes exist (most are the framework's own — autoconfiguration classes ARE configurations), the embedded server and port, the exposed actuator endpoints, and this repo's own bean distribution across its top-level packages. uptimeSeconds ticks between calls — live state, not a cached page. The controller asks one use case, the use case asks one port, and an adapter reads Spring's own runtime surfaces:"""),
                        code("ctrl:demo-controller", "java",
                                "guide/presentation/demo/SpringBootDemoController.java — the demo endpoint: same thin shape as every controller",
                                "server/src/main/java/guide/presentation/demo/SpringBootDemoController.java", """
                                @RestController
                                public class SpringBootDemoController {

                                    private final ShowAppInfo showAppInfo;

                                    public SpringBootDemoController(ShowAppInfo showAppInfo) {
                                        this.showAppInfo = showAppInfo;
                                    }

                                    /** GET /api/demo/spring/app-info → 200 live introspection of the running context. */
                                    @GetMapping("/api/demo/spring/app-info")
                                    AppInfoExhibit appInfo() {
                                        return unwrap(showAppInfo.execute());
                                    }"""),
                        code("ctrl:demo-adapter", "java",
                                "guide/data/spring/AppInfoAdapter.java — the demo's only Spring-aware file: reads Environment and ApplicationContext, emits primitives",
                                "server/src/main/java/guide/data/spring/AppInfoAdapter.java", """
                                @Override
                                public AppInfoFacts facts() {
                                    return new AppInfoFacts(
                                            environment.getProperty(NAME_PROPERTY, "unknown"),
                                            List.of(profiles == null ? new String[0] : profiles),
                                            // … build fields: null when the jar carries no build-info …
                                            readyAtEpochMillis > 0 ? readyAtEpochMillis : constructedAtEpochMillis,
                                            context.getBeanDefinitionNames().length,
                                            context.getBeanNamesForAnnotation(Configuration.class).length,
                                            guideBeanTypeNames(),
                                            ClassUtils.isPresent(TOMCAT_CLASS, getClass().getClassLoader()) ? "tomcat" : "unknown",
                                            configuredPort != null ? configuredPort : 8080,
                                            actuatorEndpoints());
                                }"""),
                        text("ctrl:demo-layers", """
                                Note the dependency direction in that chain, because it is this guide's invariant in miniature: the controller (presentation) calls a use case (domain); the adapter (data) implements a domain port — an interface whose return type, AppInfoFacts, is a record of Strings, ints, and lists. The domain never imports Environment or ApplicationContext; the adapter is the ONLY file in the chain that does. Spring's own composition root (config/SpringDemoConfig, one @Bean per object) is where the two halves meet. The same port→adapter seam the content API uses, now on Spring's own runtime state — even "framework introspection" obeys the architecture."""),
                        demo("ctrl:demo", "spring:app-info", "The running app, live",
                                "Ask the server who it is: application name and active profiles, Java version, startup time and live uptime, bean-definition and @Configuration counts, embedded server, exposed actuator endpoints, and the package layout of this repo's own beans — all read from the actual context at request time. Refresh and watch uptimeSeconds climb.")),
                section("the-request-pipeline", "The request pipeline",
                        "Embedded Tomcat → filter chain → DispatcherServlet → HandlerMapping → interceptor → controller → advice — the fixed order, and where our RequestIdFilter sits.",
                        text("pipe:order", """
                                Concept first: a Spring MVC request travels a FIXED pipeline, and knowing the order is what makes cross-cutting behavior placeable. The outermost layer is the servlet container — embedded Tomcat. Tomcat accepts the connection, parses bytes into a request/response pair, and assigns a pool thread. Next the filter chain: servlet filters are container-level machinery running BEFORE Spring's dispatcher — each sees the raw request, can wrap it, veto it, or do work around the rest of the chain.

                                Then the front controller: DispatcherServlet — the single servlet ALL of Spring MVC funnels through. It asks the HandlerMappings which handler owns this request (the route table — a lookup, not a search), runs the HandlerInterceptors' pre-handle hooks (an interception point this repo does not need yet; real ones arrive with the filters-and-interceptors topic), invokes your handler with resolved arguments, and catches what it returns or throws. Exceptions no controller-local handler catches end at the @ControllerAdvice layer — this guide's GlobalExceptionHandler."""),
                        text("pipe:request-id", """
                                This repo's own cross-cutting concern shows where a filter goes. RequestIdFilter stamps a correlation id on every request so a response, a log line, and an error envelope can all name the same request — the kind of concern that belongs OUTSIDE any controller. It extends OncePerRequestFilter — Spring's base for filters that must run exactly once per request, even when a forward re-enters the chain. The logic: honor a well-formed incoming x-request-id or generate a UUID (the pattern rejects header-injection lengths and characters), echo it onto the response, park it in the request attribute AND the logging MDC, and — the part everyone forgets — clear the MDC in finally, because the servlet thread is REUSED and one leaked id would mislabel the next request's log lines. It runs before the DispatcherServlet, so the id exists before any controller or advice — which is why the error envelope can carry it."""),
                        code("pipe:filter", "java",
                                "guide/core/logging/RequestIdFilter.java — a filter in the chain before everything Spring MVC",
                                "server/src/main/java/guide/core/logging/RequestIdFilter.java", """
                                public class RequestIdFilter extends OncePerRequestFilter {

                                    // … constants elided …

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
                                    }""")),
                section("life-of-a-request", "Life of a request",
                        "One real GET /api/content, end to end, through every layer above — naming this repo's actual classes at each stop.",
                        text("life:walk", """
                                Let one request ride the whole pipeline: GET /api/content — the call this guide's client makes to draw its topic navigation. 1) Tomcat accepts the connection on 8080 and hands a pooled thread the servlet request/response pair. 2) RequestIdFilter runs first: a UUID lands in the response header, the request attribute, and the MDC. 3) DispatcherServlet takes over and asks RequestMappingHandlerMapping, which finds the entry registered at startup: GET /api/content → ContentController.list(). 4) No interceptors apply; the handler executes.

                                5) ListTopicSummaries (a use case bean from GuideConfig) assembles the wire shape: groups from the TopicGroupId enum, one TopicSummary per registered topic. 6) The port is implemented by ContentRepositoryAdapter, answering from the in-memory ContentRegistry — the typed topic tree this page's own content comes from. 7) The TopicList record returns up the stack; Jackson 3 serializes it; 8) the pipeline unwinds, Tomcat writes the bytes, the thread returns to its pool, MDC cleaned. Total Spring knowledge required by the domain: zero — the walk crossed presentation, domain, and data with neither inner layer importing Spring, which is what let topic 01 build pure-Java exhibits and this topic bolt a demo onto the framework itself."""),
                        code("life:controller", "java",
                                "guide/presentation/ContentController.java — stop 4 of the walk: the handler the route table named",
                                "server/src/main/java/guide/presentation/ContentController.java", """
                                @RestController
                                public class ContentController {

                                    private final ListTopicSummaries listTopicSummaries;
                                    private final GetTopicById getTopicById;

                                    public ContentController(ListTopicSummaries listTopicSummaries, GetTopicById getTopicById) {
                                        this.listTopicSummaries = listTopicSummaries;
                                        this.getTopicById = getTopicById;
                                    }

                                    /** GET /api/content → 200 { version, groups, topics } */
                                    @GetMapping("/api/content")
                                    TopicList list() {
                                        return unwrap(listTopicSummaries.execute());
                                    }"""),
                        code("life:adapter", "java",
                                "guide/data/content/ContentRepositoryAdapter.java — stop 6: the port's adapter answering from the registry",
                                "server/src/main/java/guide/data/content/ContentRepositoryAdapter.java", """
                                public class ContentRepositoryAdapter implements ContentRepository {

                                    private final ContentRegistry registry;

                                    public ContentRepositoryAdapter(ContentRegistry registry) {
                                        this.registry = registry;
                                    }

                                    // …

                                    @Override
                                    public List<TopicSummary> findTopicSummaries() {
                                        return registry.getTopicSummaries();
                                    }""")),
                section("error-handling-first-steps", "Error handling: first steps",
                        "@ExceptionHandler turns thrown types into responses; the grown version is one advice for the whole app — and one error envelope contract.",
                        text("err:handler", """
                                First steps: Spring MVC lets a controller declare its own emergency exits. An @ExceptionHandler method inside a controller catches exceptions thrown by THAT controller's handlers, and returns a response as if it were a normal handler — status, body, everything. The local tool is enough for small cases; the grown version is its generalization: the same annotation moved to a @ControllerAdvice-annotated class applies to EVERY controller at once. The advice sits at the end of the pipeline — the last stop before the container's own error page — so one advice method defines the error contract for the whole application.

                                This repo goes straight to the grown version, because it made one architectural decision early: ONE error taxonomy, ONE envelope, everywhere. Failures inside the domain are VALUES (the Result type from topic 01); controllers convert a Failed Result into a typed AppException at the edge; the advice below is the only place an exception becomes HTTP. Every failure gets the typed status and code, the message, the detail map merged into the body, the filter's request id, and a log line — warn for 4xx (the client's problem), error with stack for 5xx (ours). Nothing is swallowed."""),
                        code("err:advice", "java",
                                "guide/presentation/GlobalExceptionHandler.java — the grown version: one advice, every controller, one envelope",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @RestControllerAdvice
                                public class GlobalExceptionHandler {

                                    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
                        code("err:envelope", "java",
                                "guide/core/errors/ErrorResponse.java — the envelope itself: details merged in, canonical keys winning",
                                "server/src/main/java/guide/core/errors/ErrorResponse.java", """
                                public record ErrorResponse(Map<String, Object> error) {

                                    public static ErrorResponse of(String code, String message, String requestId, Map<String, Object> details) {
                                        Map<String, Object> body = new LinkedHashMap<>();
                                        if (details != null) {
                                            body.putAll(details);
                                        }
                                        body.put("code", code);
                                        body.put("message", message);
                                        body.put("requestId", requestId);
                                        return new ErrorResponse(body);
                                    }
                                }"""),
                        text("err:contract", """
                                The envelope record is the CONTRACT, so read it as an API: every failure any client of this guide ever sees is {"error": {code, message, requestId, ...details}} — the code is machine-readable (not_found, validation_failed…, one per FailureKind enum constant from topic 01), the message is the human sentence, the requestId ties wire to log, and the failure's details map is merged underneath (a validation failure names its parameter; a not-found names its topic id). The order is load-bearing: details go in FIRST, canonical keys overwrite — details can add fields but never shadow the contract. Topic 30 makes error handling its full subject; what you keep from today is the shape: local @ExceptionHandler for local quirks, one @ControllerAdvice for the app, one envelope for every client.""")),
                section("boot-vs-spring", "Boot vs plain Spring",
                        "Spring without Boot is real and instructive — and clarifies exactly which five things Boot added.",
                        text("vs:plain", """
                                "What does Boot add?" is best answered by imagining Spring without it — that was the default for a decade. Plain Spring gave you the container and the programming model — @Component, injection, MVC — and left assembly to you: hand-picked library versions drifting into incompatibility; a web application ARCHIVE (WAR) deployed INTO an externally installed Tomcat; XML or java config wiring the web stack by hand; and ops instincts — health checks, metrics — rebuilt per project. None of it was hard once; all of it was hard the hundredth time.

                                Boot's additions, enumerated: STARTERS — the capability-shaped dependency sets from the first section. AUTOCONFIGURATION — the conditional factories from the second. THE EMBEDDED SERVER — Tomcat (or Jetty/Undertow, one property each) as a library the app CONTAINS rather than a container the app deploys into; the artifact became a jar that runs with java -jar — which is why this repo's compose file can say mvn spring-boot:run and get a server with zero installation. ACTUATOR — the ops endpoints (next section). DEVTOOLS — development-loop conveniences (below). And the EXECUTABLE JAR format itself: your classes plus every dependency under BOOT-INF/, launched by Boot's own launcher — "ship the server" is scp one file."""),
                        text("vs:plugin", """
                                The maven plugin is the packaging half of that last item — it is in our pom precisely so mvn package produces that executable jar rather than a plain library jar, and so mvn spring-boot:run runs the app in-place during development (the compose file leans on that). The honest summary: not "more features" but OPINIONS ASSEMBLED — the pieces the XML era arranged by hand, pre-arranged into a convention that starts, serves, ships, and reports, every arrangement reversible (the next section)."""),
                        code("vs:plugin-xml", "xml",
                                "server/pom.xml — the plugin that turns mvn package output into java -jar server.jar",
                                "server/pom.xml", """
                                <build>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                        </plugin>
                                    </plugins>
                                </build>""")),
                section("what-it-leaves-out", "What it leaves out",
                        "Boot ships no web page, no database, no ORM by default — opinionated but overridable, and this repo exercises both halves of that contract.",
                        text("left:philosophy", """
                                The surprising Boot fact, and the mark of its taste: the core adds almost nothing application-shaped. No ORM. No web pages. No database driver, no connection pool, no cache implementation. Our whole runtime list is web, validation, actuator — capability starters for things the app DOES. The philosophy: Boot autoconfigures what you BROUGHT, and what you did not bring, it leaves alone (you saw DataSourceAutoConfiguration politely report "did not match — class not found"). Opinionated but overridable, in both directions: bring a starter and receive the curated wiring; override a bean or property and the framework backs off.

                                Why so austere? Because every bundled default becomes a compatibility promise, and defaults rot. The conditional-factory design lets Boot ship integration code for a hundred libraries while ACTIVATING only the ones your classpath names — the report's negative-matches column IS the restraint, made visible. "Explicit deps" is the list of things your app has decided to be."""),
                        text("left:divergence", """
                                This repo diverges from even the common Boot demo path in one deliberate way worth naming: PostgreSQL only, no H2. The conventional demo bundles in-memory H2 so samples run with zero setup — convenient, and quietly dishonest: H2 accepts sloppy SQL, forgives concurrency, and vanishes on restart. This guide's decision log records the opposite call: ONE dialect, tests against the real engine (Testcontainers in the data topics) — a guide that diverges from its production database teaches a different language than it claims. The compose file says it in one comment — and the postgres service it names is a real, stateful container the app will meet again in the SQL topics. The standard for any default: not "what makes the demo run" but "what survives production"."""),
                        code("left:pg", "yaml",
                                ".docker/docker-compose.yml — the divergence, stated where it is implemented",
                                ".docker/docker-compose.yml", """
                                # PostgreSQL 17 — the guide's only SQL engine (no H2: one dialect, tests
                                # against the real thing). guide/guide/guide.
                                postgres:
                                    image: postgres:17""")),
                section("static-and-actuator", "Static resources and actuator",
                        "What Boot serves before and around your API: static mounts, the actuator endpoints we expose, and the docs mount that arrives with a later topic.",
                        text("act:static", """
                                Boot's web stack serves more than your controllers. By default, static content is mounted from four classpath locations — /static, /public, /resources, and /META-INF/resources — mapped at the URL root: drop index.html into src/main/resources/static/ and the app grows a landing page; drop a hashed-asset build there and it serves a frontend. The precedence rule: your route table WINS — a controller mapping at the same path takes precedence over the static file, so API and assets share one origin without collisions. That is why the prod stack needs no separate file server for the client.

                                The second thing served around your API is the actuator. It exposes operational information over HTTP at /actuator/*, and true to the override-everything contract, what it publishes is itself a configuration property. Our application.yml names the web-exposed endpoints — health and info, plus prometheus since topic 33 (observability) tours the exposition — and that file IS the source of truth: the demo in the controller section reads this very property back out of the Environment."""),
                        code("act:yml", "yaml",
                                "server/src/main/resources/application.yml — the whole running-process configuration, verbatim",
                                "server/src/main/resources/application.yml", """
                                spring:
                                  application:
                                    name: springboot-developers-guide

                                server:
                                  port: 8080

                                management:
                                  endpoints:
                                    web:
                                      exposure:
                                        include: health,info,prometheus
                                  endpoint:
                                    health:
                                      probes:
                                        enabled: true"""),
                        text("act:health", """
                                Read the file as the running process's shape: a name, the port Tomcat binds, the management block. /actuator/health answers {"status":"UP"} plus component detail once real dependencies register health indicators — and the probes flag switches on the kubernetes-shaped pair /actuator/health/liveness and /actuator/health/readiness, the difference between "restart me" and "stop routing to me" (topic 33 expands this). /actuator/info answers build metadata when the build provides it. Deliberately NOT exposed: env, beans, mappings — endpoints that leak configuration and internals; the exposure list is the least-privilege decision, and ours is two endpoints long. The /docs mount you might expect from the express guide's sibling project arrives later, with the api-documentation topic.""")),
                section("dev-workflow", "The dev workflow",
                        "Devtools restart semantics, and the real loop in THIS repo: bind-mounted source, mvn spring-boot:run, tests in the tooling container.",
                        text("dev:devtools", """
                                spring-boot-devtools is the development-loop module — runtime-scoped and optional in our pom, so it exists on your machine and never in the shipped jar (Boot excludes it from repackaged jars by default). Its headline feature is the automatic restart: devtools watches the classpath, and when your compiled classes change it RESTARTS the application context — but not the JVM. The mechanism is two classloaders: your classes live in a restart classloader thrown away and rebuilt on change; dependency jars stay in a persistent base classloader. Since most of the classpath is stable libraries, re-loading only your classes makes restarts land in seconds. The trigger to know: devtools watches CLASSPATH FILES, so it fires on compiled output — IDE builds trigger it directly.

                                Equally worth knowing is what devtools is NOT: not JRebel-style hot swap (changed method bodies can swap via the debugger; changed signatures cannot) and not a production feature. A restart is a cold context: caches empty, in-memory state gone (this guide's ScenarioStore resets to its environment-configured defaults)."""),
                        text("dev:this-repo", """
                                This repo's loop composes devtools with Docker; the compose file IS the documentation. The server service runs the official maven image with mvn spring-boot:run against a BIND-MOUNTED /srv — the host's server/ directory — so compiled classes land on the classpath devtools watches, and anonymous volumes keep build output (target/) inside the container's layer instead of littering the host tree. SPRING_PROFILES_ACTIVE=dev rides in as an environment variable — the same Environment mechanism the GuideConfig quote showed, now powering a profile. Edit a file and the running container recompiles through the bind mount; devtools restarts in seconds. No host Maven, no host node_modules — the host stays clean BY DESIGN; every build artifact lives in Docker."""),
                        code("dev:compose", "yaml",
                                ".docker/docker-compose.yml — the server service: bind-mounted source, spring-boot:run, devtools restarts",
                                ".docker/docker-compose.yml", """
                                # Spring Boot dev server: mvn spring-boot:run against the bind-mounted
                                # source (devtools hot reload). Maven dependencies cache in the container's
                                # writable layer until it is recreated.
                                server:
                                    image: maven:3.9-eclipse-temurin-25
                                    container_name: springboot_developers_guide_server_dev
                                    restart: unless-stopped
                                    working_dir: /srv
                                    command: mvn -q spring-boot:run
                                    ports:
                                      - 8080:8080
                                    environment:
                                      - SPRING_PROFILES_ACTIVE=dev
                                    volumes:
                                      # Anonymous volume over /srv/target: Maven build output stays in Docker
                                      # and never lands in the host's server/ tree.
                                      - ./../server:/srv
                                      - /srv/target"""),
                        code("dev:devcontainer", "json",
                                ".devcontainer/devcontainer.json — one attach starts the whole stack; terminals land in the tooling container",
                                ".devcontainer/devcontainer.json", """
                                {
                                  "name": "springboot-developers-guide",
                                  "dockerComposeFile": "../.docker/docker-compose.yml",
                                  "service": "dev",
                                  "runServices": ["dev", "server", "client", "postgres", "redis", "mongo"],
                                  "workspaceFolder": "/workspaces/springboot_developers_guide",
                                  "shutdownAction": "stopCompose",
                                  // … editor customizations elided …
                                }"""),
                        text("dev:tests", """
                                Where do tests run? In the dev tooling container — the compose dev service, a JDK 25 + Maven + Node image that runs no application, just sleeps and waits to be your terminal. Open the repo in the devcontainer and VS Code attaches there; mvn test from that terminal runs against the same bind-mounted source, with the full stack (server, client, postgres, redis, mongo) already up. The division of labor: app containers run apps; the tooling container runs builds, tests, and git; the host runs Docker and a browser. Every verification in this guide's development loop — including the suite that verifies every snippet on this page is quoted from real source — runs through that container, against that source.""")),
                section("terminal-companion", "The endpoints from a terminal",
                        "Every topic ends at a terminal: the health pair, the actuator pair, and the demo — driven by curl.",
                        text("term:tour", """
                                curl closes the loop between the prose and the process. Three groups of endpoints repay hand-typing. The health pair shows the boundary between OUR API and the framework's: /api/health is a controller we wrote (route table, Jackson, the whole pipeline); /actuator/health is the framework's built-in answering on the same port — comparing them compares your code's contract with Boot's. The demo endpoint rewards REPEATED calls: uptimeSeconds is computed per request, two curls a few seconds apart prove it is live introspection, not a cached snapshot — and the counts will not match this page's numbers, because they describe the process you are running. And one deliberate error: ask for a topic that does not exist and watch the pipeline's last layer render the envelope. Start the stack, then take the tour:"""),
                        code("term:curl", "bash", "the curl tour of topic 04", null, """
                                # Our API's liveness — a controller we wrote, through the full pipeline:
                                curl -s localhost:8080/api/health

                                # The framework's liveness — same port, built-in, no code of ours:
                                curl -s localhost:8080/actuator/health

                                # The readiness probe our application.yml enabled:
                                curl -s localhost:8080/actuator/health/readiness

                                # The demo — the running context describing itself:
                                curl -s localhost:8080/api/demo/spring/app-info | head -40

                                # … again a few seconds later: uptimeSeconds climbs — live state, not cache:
                                curl -s localhost:8080/api/demo/spring/app-info | grep uptimeSeconds

                                # This topic's own content, served through the pipeline it describes:
                                curl -s localhost:8080/api/content/spring-boot-fundamentals | head -60

                                # The error envelope contract, live — unknown topic, typed 404, requestId:
                                curl -i localhost:8080/api/content/does-not-exist""")));
    }
}
