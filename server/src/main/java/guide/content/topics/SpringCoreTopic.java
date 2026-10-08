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
 * Topic 05 — Spring Core: The IoC Container. The Spring-specific heart of the
 * guide: what inversion of control actually buys, what the container IS, and
 * every wiring concern the app will ever hit — bean definitions, injection
 * styles, ambiguity, lifecycle, scopes, conditionals, events, value
 * resolution, circularity. Anchored in THIS app's real wiring: the
 * composition root in config/ is quoted, not invented, and the topic's live
 * demo (core:container) makes the running context inspect itself through the
 * same port→adapter seam every topic uses.
 */
public final class SpringCoreTopic {

    private SpringCoreTopic() {
    }

    public static Topic topic() {
        return new Topic("spring-core", "Spring Core: The IoC Container",
                "The container behind every Boot app: bean definitions and where they come from, "
                        + "constructor-first injection, resolving ambiguity, the bean lifecycle, scopes, "
                        + "profiles and conditionals, events, value resolution — and why circular "
                        + "dependencies failing fast is a feature.",
                TopicGroupId.FUNDAMENTALS, 5,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-ioc", "Why inversion of control",
                        "Construct this app's object graph by hand once, on paper — then let the container do it. What the container actually takes over, and what it asks in return.",
                        text("ioc:pain", """
                                Do this exercise once and dependency injection stops being a buzzword. Take three of this repo's real classes — ContentController, the use case GetTopicById, and ContentRepositoryAdapter — and construct them by hand in a main method. You need a ContentRegistry, so build that first (it needs the full topic list). Wrap it in the adapter, because GetTopicById's constructor takes a ContentRepository, not a registry. Then the use case. Then the controller. Now do it again for every other controller, every demo this guide adds, every test. That assembly code is real code: it rots, it duplicates, and it knows the IMPLEMENTATION of every layer above it — your main method imports the data layer directly, so the dependency rule exists only at your own discretion.

                                Inversion of control is the move where you stop assembling and start DECLARING. Each class states what it needs (a constructor parameter); a third party — the container — reads those statements, builds every object, and injects each dependency where it was asked for. Control over construction inverts: from your main method to the framework. What you bought is exactly the pain you just simulated: no assembly code anywhere, one implementation choice per type made in one visible place, and swapping the choice means editing one line — which is how this guide will hand the same port a live adapter or a fake one without touching a single use case."""),
                        code("ioc:guide-config", "java",
                                "guide/config/GuideConfig.java — the composition root: the container does the assembling; this file does the declaring",
                                "server/src/main/java/guide/config/GuideConfig.java", """
                                @Configuration
                                public class GuideConfig {

                                    @Bean
                                    ContentRegistry contentRegistry() {
                                        return new ContentRegistry(ContentTopics.all());
                                    }

                                    @Bean
                                    ContentRepository contentRepository(ContentRegistry registry) {
                                        return new ContentRepositoryAdapter(registry);
                                    }"""),
                        text("ioc:price", """
                                Read that quote as the entire contract. @Configuration marks a class whose methods DESCRIBE objects; each @Bean method is one declaration. contentRepository(ContentRegistry registry) never calls contentRegistry() itself — the parameter is filled by the container, which already holds the bean that method produced. Nothing here news up a dependency inside business code, and nothing imports an implementation from the layer below: GuideConfig is the ONE file allowed to know both sides of every seam. That is the composition-root pattern, and this repo's discipline is total — every port-to-adapter and every use case is an explicit line in config/, never a scan surprise.

                                What the container asks in return: your classes must be constructible by it (one constructor Spring can call, dependencies as parameters), and you accept that construction is the framework's job now. The price is a layer of indirection and startup-time wiring you cannot step through — which is why this topic exists: the container is simple once you can SEE it, and the demo in the next section makes the running context show itself.""")),
                section("application-context", "The ApplicationContext",
                        "The container as a running object: BeanFactory vs ApplicationContext, the refresh/boot sequence, and the live demo where the running context describes its own beans.",
                        text("ctx:what", """
                                The ApplicationContext IS the container — a Java object this application has exactly one of, created inside SpringApplication.run(). Everything with a @Bean method or a stereotype annotation has a definition registered into it; everything your code injects is a reference it hands out. Two facts give it shape. First: ApplicationContext EXTENDS BeanFactory — the bean container interface (getBean, definitions, scopes) — so "BeanFactory vs ApplicationContext" is not a choice between rivals but a core and its supersets. Plain BeanFactory is the lazy registry: definitions sit idle, a bean materializes when first asked for. ApplicationContext eagerly refreshes singletons at startup, publishes application events, understands annotation injection, layers internationalization and resource loading on top. Boot always builds the full ApplicationContext — you meet BeanFactory mainly as the type of the context's internal getBeanFactory().

                                Second: refresh() is the moment the container becomes real. Simplified to the steps that matter: read all bean definitions (component scan, @Bean methods, autoconfiguration candidates); run the BeanFactoryPostProcessors — including ConfigurationClassPostProcessor, the machinery that parses @Configuration classes; register BeanPostProcessors, the hooks that wrap beans (@Transactional proxies are born here — topic 06); instantiate every non-lazy singleton in dependency order, injecting and running lifecycle callbacks as each materializes; finish initialization, publish ContextRefreshedEvent; then Boot starts the web server and publishes ApplicationReadyEvent. Failures in this phase — a missing dependency, a circular constructor — kill startup. That is the fail-fast contract: a broken graph never serves a single request."""),
                        code("ctx:adapter", "java",
                                "guide/data/core/container/BeansAdapter.java — the demo's adapter walking the real bean factory",
                                "server/src/main/java/guide/data/core/container/BeansAdapter.java", """
                                @Override
                                public ContainerFacts snapshot() {
                                    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
                                    List<BeanSnapshot> beans = new ArrayList<>();
                                    for (String beanName : beanFactory.getBeanDefinitionNames()) {
                                        try {
                                            beans.add(snapshotOf(beanFactory, beanName));
                                        } catch (RuntimeException skipped) {
                                            // unresolvable bean definition — report what resolved
                                        }
                                    }
                                    return new ContainerFacts(List.copyOf(beans));
                                }"""),
                        text("ctx:demo-intro", """
                                The live demo asks that running container to describe itself. GET /api/demo/core/beans returns the whole picture as data: how many bean definitions exist, this repo's own beans grouped by top-level package, the beans matching the registered use-case naming conventions, and one sample bean with its scope, factory method, and the bean names it depends on — the object graph of the why-ioc section, rendered as JSON by the graph itself. GET /api/demo/core/beans/{name} zooms into any bean; an unknown name answers with the typed 404 envelope. The adapter above is the only Spring-aware file in the chain — it walks getBeanDefinitionNames() defensively (a bean that will not resolve is skipped, never a 500) and hands plain records to a domain use case. Reading Spring's own API IS this demo's feature, and it still lives behind a port, so the domain exhibit is testable without a container."""),
                        code("ctx:controller", "java",
                                "guide/presentation/demo/CoreDemoController.java — the thin controller in front of the demo",
                                "server/src/main/java/guide/presentation/demo/CoreDemoController.java", """
                                @RestController
                                public class CoreDemoController {

                                    private final ShowContainer showContainer;
                                    private final InspectBean inspectBean;

                                    public CoreDemoController(ShowContainer showContainer, InspectBean inspectBean) {
                                        this.showContainer = showContainer;
                                        this.inspectBean = inspectBean;
                                    }

                                    /** GET /api/demo/core/beans → 200 overview of the running context. */
                                    @GetMapping("/api/demo/core/beans")
                                    ContainerExhibit beans() {
                                        return unwrap(showContainer.execute());
                                    }"""),
                        demo("ctx:demo", "core:container", "The container, showing itself",
                                "The running ApplicationContext as data: total bean definitions, this repo's beans by package, the use-case beans claimed by the registered naming conventions, and a sample bean with its scope, factory method, and dependencies. Type any bean name for its detail — class, scope, factory method, dependency names, and whether the container hands out a proxy. Unknown names return the typed 404 envelope.")),
                section("bean-definitions", "Bean definitions",
                        "Where beans come from: stereotype annotations on classes vs explicit @Bean methods in configuration — and why this repo chose the explicit discipline.",
                        text("def:two-spellings", """
                                A bean definition is the container's recipe: a name, a type, how to construct it, what it depends on, its scope. Everything else in this topic operates on definitions. There are exactly two spellings for contributing them. The first: put a stereotype annotation on the CLASS — @Component, or its layered aliases @Service, @Repository, @Controller (and @RestController via @Controller) — and let component scan find it. The stereotype says "I am a class that IS a bean"; the scanner registers a definition whose factory is 'instantiate this class', and the bean name defaults to the decapitalized class name. The aliases carry historical semantics — @Repository turns data-access exceptions into the typed hierarchy, @Controller marks web handlers — but to the container they are all @Component.

                                The second spelling: an @Bean METHOD inside a @Configuration class. The method's return type is the bean's type, its parameters are its dependencies, its body is the factory, the method name is the bean name. Same definition, different placement — the stereotype puts wiring knowledge inside the class ("I exist"), @Bean keeps it in the config file ("we decided you exist")."""),
                        code("def:stereotype", "java",
                                "guide/presentation/HealthController.java — the stereotype spelling: the annotation on the class IS the definition",
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
                        code("def:explicit", "java",
                                "guide/config/CoreDemoConfig.java — the @Bean spelling: the method is the factory, its parameters the dependencies",
                                "server/src/main/java/guide/config/CoreDemoConfig.java", """
                                @Configuration
                                public class CoreDemoConfig {

                                    @Bean
                                    ContainerSnapshotSource containerSnapshotSource(ConfigurableApplicationContext context) {
                                        return new BeansAdapter(context);
                                    }"""),
                        text("def:our-discipline", """
                                This repo's discipline is deliberate: controllers are the ONLY scanned beans (presentation classes are naturally self-describing), while every port-to-adapter and every use case is an explicit @Bean in config/. Three reasons, worth copying. GREPPABLE: one file (or one per topic) answers "what implements ContentRepository" — no search across every package for an annotation. NO SURPRISES: scanning registers anything it finds under the root package; explicit registration fails loudly for a bean you forgot instead of silently succeeding for beans you never meant. ARCHITECTURE-PRESERVING: implementation choices (data adapters implementing domain ports) are visible in exactly the layer that owns architecture decisions. The demo's detail endpoint makes the difference visible per bean: scanned components show factoryMethod null — the container instantiates the class itself — while registered beans show the @Bean method's name, your factory, in the metadata.""")),
                section("injection-styles", "Injection styles",
                        "Field, setter, constructor — and why this repo, like most modern Spring, injects exactly one way: through the constructor.",
                        text("inj:three", """
                                Spring offers three places to receive a dependency. FIELD injection writes into the field directly — @Autowired on a private field; the worst choice, for reasons below. SETTER injection calls a setter after construction — historically for optional dependencies. CONSTRUCTOR injection passes dependencies as constructor parameters — and if a class has exactly one constructor, no annotation is needed at all; the parameter list IS the @Autowired. That rule is why the classes in this guide have annotation-free constructors: ContentController below declares two use cases and Spring feeds both, no marker anywhere.

                                Constructor-first, the arguments: FINAL fields — injected state becomes immutable, and immutable state is thread-safe by construction (the scopes section returns to this); IMPOSSIBLE HALF-BUILT STATE — a constructor-injected object is either fully wired or never exists, versus a field-injected object constructible with nulls that NPEs three layers later; TESTABILITY WITHOUT REFLECTION — a unit test calls the constructor with a fake, no container, no reflection tricks; HONEST API — the parameter list is machine-checkable documentation of what the class cannot live without. Field injection defeats every one of these."""),
                        code("inj:controller", "java",
                                "guide/presentation/ContentController.java — annotation-free constructor injection: one constructor, no @Autowired anywhere",
                                "server/src/main/java/guide/presentation/ContentController.java", """
                                @RestController
                                public class ContentController {

                                    private final ListTopicSummaries listTopicSummaries;
                                    private final GetTopicById getTopicById;

                                    public ContentController(ListTopicSummaries listTopicSummaries, GetTopicById getTopicById) {
                                        this.listTopicSummaries = listTopicSummaries;
                                        this.getTopicById = getTopicById;
                                    }"""),
                        code("inj:usecase", "java",
                                "guide/domain/spring/ShowAppInfo.java — the same style all the way down: final fields, guarded constructor, no Spring import",
                                "server/src/main/java/guide/domain/spring/ShowAppInfo.java", """
                                public class ShowAppInfo {

                                    private final AppInfoSource source;
                                    private final LongSupplier clock;

                                    public ShowAppInfo(AppInfoSource source, LongSupplier clock) {
                                        this.source = Objects.requireNonNull(source, "source");
                                        this.clock = Objects.requireNonNull(clock, "clock");
                                    }"""),
                        code("inj:bean-methods", "java",
                                "guide/config/SpringDemoConfig.java — @Bean methods inject the same way: parameters of the factory method",
                                "server/src/main/java/guide/config/SpringDemoConfig.java", """
                                @Bean
                                AppInfoSource appInfoSource(Environment environment, ApplicationContext context,
                                        ObjectProvider<BuildProperties> buildProperties) {
                                    return new AppInfoAdapter(environment, context, buildProperties.getIfAvailable());
                                }

                                @Bean
                                ShowAppInfo showAppInfo(AppInfoSource appInfoSource) {
                                    return new ShowAppInfo(appInfoSource, System::currentTimeMillis);
                                }"""),
                        text("inj:legit-setter", """
                                Where setter injection remains legitimately better: genuinely OPTIONAL or RECONFIGURABLE dependencies — rare, and usually a sign the class wants splitting. This repo has none: every dependency in every constructor is load-bearing, which is exactly what the style makes visible. The @Bean-method quote adds one wrinkle: the factory method's parameters are resolved by the SAME machinery — Environment, ApplicationContext, and an ObjectProvider flow into appInfoSource exactly as they would into a component's constructor. One injection model, two definition spellings; the previous section's distinction is only about where definitions live, never about how wiring behaves.""")),
                section("wiring-ambiguity", "Wiring ambiguity",
                        "Two beans, one type: @Qualifier, @Primary, collecting all implementations as a List — plus ObjectProvider for the optional case. All four appear in this repo's real config.",
                        text("wire:problem", """
                                Inject by type, and the container needs exactly one candidate. The moment two beans share a type — two caches, two adapters, two implementations of one port — injection by that type alone is ambiguous and startup fails with NoUniqueBeanDefinitionException naming the rivals. Boot's own beans make this common: define your ObjectMapper and you are the second ObjectMapper in the context. Three resolutions, all declarative. @Primary marks one bean the default — inject-the-type keeps working everywhere, choosing the primary. @Qualifier narrows ONE injection point to a named bean — surgical, local, self-documenting at the exact parameter that needed the choice. Or embrace the multiplicity: declare the parameter as List<InterfaceType> and the container collects EVERY implementation into an ordered list — the right tool when the consumers genuinely want all of them.

                                This repo's live example is the container demo itself. Two UseCaseConvention beans implement one interface; ShowContainer does not pick — it takes them all as a List and honors any match. That is the strategy-pattern-as-wiring: each convention is one small class, a new naming style joins by adding one @Bean, and nothing downstream is edited. The ordered list follows @Order or registration order, and the pair shows up honestly in the demo's own output — the conventions list names both."""),
                        code("wire:pair", "java",
                                "guide/config/CoreDemoConfig.java — two beans, one interface, consumed as a List: ambiguity turned into a feature",
                                "server/src/main/java/guide/config/CoreDemoConfig.java", """
                                @Bean
                                UseCaseConvention layeredUseCaseConvention() {
                                    return new LayeredUseCases();
                                }

                                @Bean
                                UseCaseConvention suffixedUseCaseConvention() {
                                    return new SuffixedUseCases();
                                }

                                @Bean
                                ShowContainer showContainer(ContainerSnapshotSource containerSnapshotSource,
                                        List<UseCaseConvention> useCaseConventions) {
                                    return new ShowContainer(containerSnapshotSource, useCaseConventions);
                                }"""),
                        code("wire:provider", "java",
                                "guide/config/SpringDemoConfig.java — ObjectProvider: an optional dependency resolved lazily, null-safe at the call site",
                                "server/src/main/java/guide/config/SpringDemoConfig.java", """
                                /** ObjectProvider: BuildProperties exists only when build-info was generated. */
                                @Bean
                                AppInfoSource appInfoSource(Environment environment, ApplicationContext context,
                                        ObjectProvider<BuildProperties> buildProperties) {
                                    return new AppInfoAdapter(environment, context, buildProperties.getIfAvailable());
                                }"""),
                        text("wire:provider-why", """
                                The fourth tool covers a different shape of uncertainty: not "which one" but "does it exist at all". BuildProperties is a Boot bean that exists ONLY when the build generated build-info — sometimes present, sometimes not. Injecting it directly would make presence mandatory; injecting ObjectProvider<BuildProperties> defers the question: the provider itself always injects, and getIfAvailable() answers null when no such bean exists (getObject() when absence should be an error). The provider is also lazy — nothing resolves until asked — which is why the same type doubles as a circularity breaker in a later section. Decision table for this repo: multiple beans and one is the sensible default → @Primary; the choice differs per consumer → @Qualifier; the consumer wants all of them → List; existence itself is conditional → ObjectProvider.""")),
                section("bean-lifecycle", "The bean lifecycle",
                        "Construction is the middle of a bean's story: the full callback order from instantiation to destruction, and where this app's ApplicationReadyEvent timestamp really sits.",
                        text("life:order", """
                                A singleton's life through the container, in order. INSTANTIATION — the constructor runs (constructor injection happens HERE, as arguments). POPULATE — fields and setters inject (@Autowired fields, if you use them). AWARE CALLBACKS — beans implementing BeanNameAware, BeanFactoryAware, ApplicationContextAware are handed their own metadata; the escape hatch of last resort, useful for infra objects that must FIND beans dynamically (the container demo's adapter gets the same power cleaner: the context is constructor-injected). INIT — in order: @PostConstruct methods; InitializingBean.afterPropertiesSet(); then a custom init-method named by the definition. The bean is complete and in service. DESTROY — at shutdown, in reverse: @PreDestroy methods, DisposableBean.destroy(), then destroy-methods — and by default the container calls close() on any auto-closeable bean, which is why a connection pool shutters itself without configuration.

                                Two practical notes. First, @PostConstruct is for validation and warmup that must see FINAL state — the Jakarta annotation works on scanned and registered beans alike, and is the honest choice when a bean cannot be correct until it checks itself after wiring. Second, destruction callbacks only fire for singletons on a graceful shutdown (SIGTERM, context.close()) — killed -9 processes run none, which is why data topics put correctness in transactions rather than shutdown hooks. This app's real lifecycle callback is Boot-flavored: AppInfoAdapter implements ApplicationListener and captures ApplicationReadyEvent, because "when did the app start" should mean "when did Boot report READY" — after the web server binds — not "when was this one bean constructed"."""),
                        code("life:adapter", "java",
                                "guide/data/spring/AppInfoAdapter.java — a real lifecycle participant: the READY event captured, with a construction-time fallback",
                                "server/src/main/java/guide/data/spring/AppInfoAdapter.java", """
                                public final class AppInfoAdapter implements AppInfoSource, ApplicationListener<ApplicationReadyEvent> {
                                    // … fields and constructor elided: constructedAtEpochMillis is the fallback …

                                    @Override
                                    public void onApplicationEvent(ApplicationReadyEvent event) {
                                        readyAtEpochMillis = System.currentTimeMillis();
                                    }

                                    // … facts() reports the timestamp; ready wins when the event fired: …
                                    readyAtEpochMillis > 0 ? readyAtEpochMillis : constructedAtEpochMillis,"""),
                        text("life:design-lesson", """
                                The design lesson in that quote is bigger than the timestamp. The listener field is written ONCE, at a known moment, then only read — so a plain volatile long is correct under this app's request concurrency, with no locks anywhere. The fallback handles contexts where READY never fires (a sliced test context, a request racing startup) — a lifecycle participant that assumes its callback always ran is a bug waiting for the first odd context. And notice what the adapter does NOT do: no @PostConstruct, no afterPropertiesSet — its constructor already receives everything it needs, because constructor injection collapsed construction and configuration into one step. Most beans in a well-wired app need NO init callback; needing one is worth a comment explaining why the constructor was not enough.""")),
                section("scopes", "Scopes",
                        "Singleton by default — and why one instance serving every request is safe. Prototype, the web scopes, and the stateless-services argument from this app's own beans.",
                        text("scopes:default", """
                                Every bean so far is a singleton: one instance, created at refresh, shared by every request for the life of the context. That sounds like a hazard and is in fact the design's chief virtue — PROVIDED the singleton is stateless. The thread-safety argument, precisely: servlet requests arrive on many threads (virtual threads in this app), so a singleton's methods run concurrently; if the bean holds no mutable state, there is nothing to race on. Every field is final, set once by the constructor, read-only thereafter — ShowAppInfo and every use case in this guide is exactly that shape, which is why THIS app serves concurrent requests from single instances with no synchronized keyword in the domain. Per-request state lives in method locals, in records, on the request itself — never on the bean.

                                PROTOTYPE is the other core scope: the container hands out a NEW instance per getBean/injection point, and manages only creation — destruction callbacks do NOT run (the caller owns the object). Use it for genuinely stateful, short-lived collaborators; injecting a prototype into a singleton injects it ONCE (the singleton lives forever), the classic trap — ObjectProvider or List re-solves per call when you need fresh-each-time. The web scopes — request, session, application — extend this to HTTP lifetimes; a request-scoped bean injected into a singleton needs a SCOPED PROXY: the singleton holds a placeholder that resolves the real instance per request. This app defines no scoped beans — deliberately, because stateless services do not need them — and the proxied flag in the bean detail exists precisely so the day a proxy appears there, you will SEE it in the data."""),
                        code("scopes:snapshot", "java",
                                "guide/data/core/container/BeansAdapter.java — the scope read straight from the definition: empty means the default, singleton",
                                "server/src/main/java/guide/data/core/container/BeansAdapter.java", """
                                private BeanSnapshot snapshotOf(ConfigurableListableBeanFactory beanFactory, String beanName) {
                                    Class<?> type = context.getType(beanName);
                                    String scope = beanFactory.getBeanDefinition(beanName).getScope();
                                    String factoryMethod = beanFactory.getBeanDefinition(beanName).getFactoryMethodName();
                                    // … dependencies read next, then the fact is assembled: …
                                    return new BeanSnapshot(
                                            beanName,
                                            type != null ? type.getName() : "unknown",
                                            scope == null || scope.isBlank() ? "singleton" : scope,
                                            factoryMethod,
                                            List.of(dependencies == null ? new String[0] : dependencies),
                                            isProxied(beanName));
                                }""")),
                section("profiles-and-conditionals", "Profiles and conditionals",
                        "Boot-time selection (@Profile, the @Conditional family) versus this app's RUNTIME ScenarioStore switch — an honest comparison of the two mechanisms.",
                        text("cond:boot-time", """
                                Two families decide WHICH beans exist. @Profile tags a bean or config class with named environments — @Profile("dev") on the H2 variant, @Profile("prod") on the Postgres one — and the active profiles (spring.profiles.active, environment variable, per this repo's compose file) select at STARTUP: non-matching definitions never register. It is coarse, environment-shaped, and binary. The @Conditional family is the fine-grained engine UNDER everything — @ConditionalOnClass, @ConditionalOnMissingBean, @ConditionalOnProperty evaluated on bean definitions during refresh; topic 04 showed it powering autoconfiguration, and @Profile is itself sugar over it. @ConditionalOnProperty deserves a place in your vocabulary: it binds a bean's existence to a configuration flag — feature toggles at the wiring level, e.g. @ConditionalOnProperty("guide.demo.feature-x.enabled", havingValue = "true").

                                What both families share is WHEN they decide: once, at refresh. The application that started is the application that runs. This repo deliberately ALSO runs the other experiment — the ScenarioStore is a RUNTIME switch: the same beans, the same wiring, and a mutable store that data adapters consult per request. PATCH /api/demo/scenario flips fake/live mode or injects a failure, and the next request behaves differently — no restart. The tradeoffs are honest: boot-time conditionals are immutable, testable at startup, invisible at runtime; a runtime switch is live and demoable but mutable state — so ScenarioStore is exactly ONE bean, volatile-guarded, synchronized on write, resettable to its environment-configured initial state. Rule of thumb this repo follows: environment differences (which database, which credentials) belong to profiles and properties; behavioral differences you want to WATCH (fake vs live data, failure injection) belong behind an explicit runtime seam."""),
                        code("cond:scenario", "java",
                                "guide/core/scenario/ScenarioStore.java — the runtime switch: mutable by design, and only THIS carefully",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                public final class ScenarioStore {

                                    private final ScenarioState initial;
                                    private final Map<String, ScenarioDataMode> areaModes = new ConcurrentHashMap<>();
                                    private volatile ScenarioState state;
                                    // … constructors elided …

                                    public synchronized ScenarioState apply(ScenarioPatch patch) {
                                        Objects.requireNonNull(patch, "patch");
                                        ScenarioState current = state;
                                        ScenarioState next = new ScenarioState(
                                                patch.dataMode() != null ? patch.dataMode() : current.dataMode(),
                                                patch.injectFailure() != null ? patch.injectFailure() : current.injectFailure());
                                        state = next;
                                        return next;
                                    }""")),
                section("application-events", "Application events",
                        "The container as an event bus: ApplicationEventPublisher, @EventListener, synchronous-by-default semantics, and this app's real ApplicationReadyEvent listener.",
                        text("evt:mechanics", """
                                Any bean can broadcast to any other bean without either holding a reference to the other: inject ApplicationEventPublisher (or the ApplicationContext, which extends it), call publishEvent(something), and every matching listener runs. Listeners register by annotation — @EventListener on a method whose PARAMETER TYPE selects the events it wants — or by implementing ApplicationListener<T>, which is what AppInfoAdapter does for ApplicationReadyEvent. Payload events need no ceremony: any object publishes; the type is the routing key. Spring's own container lifecycle is delivered this way (ContextRefreshedEvent, ContextClosedEvent), and Boot layers its timeline on top — ApplicationReadyEvent fires once the web server is bound and everything before it succeeded.

                                The semantic everyone must know: listeners run SYNCHRONOUSLY, on the publisher's thread, inline — publishEvent returns after every listener completed. That makes events a clean in-process fan-out with real transactional guarantees (a listener joins the publisher's transaction) and NO decoupling of failure or latency — a slow listener stalls the publisher; a throwing listener unwinds the publish call. When you want fire-and-forget on a pool, @Async on the listener moves it to an executor (a background-jobs preview: @Async needs @EnableAsync and a configured executor, and async listeners lose the caller's transaction). This repo's single listener is deliberately synchronous and one line: the READY timestamp."""),
                        code("evt:listener", "java",
                                "guide/data/spring/AppInfoAdapter.java — the whole real listener: type-routed, synchronous, one write",
                                "server/src/main/java/guide/data/spring/AppInfoAdapter.java", """
                                public final class AppInfoAdapter implements AppInfoSource, ApplicationListener<ApplicationReadyEvent> {
                                    // … the adapter's AppInfoSource reads elided; the field below is written once, on READY: …
                                    private volatile long readyAtEpochMillis;

                                    @Override
                                    public void onApplicationEvent(ApplicationReadyEvent event) {
                                        readyAtEpochMillis = System.currentTimeMillis();
                                    }
                                }""")),
                section("spel-and-value", "@Value, SpEL, and the Environment",
                        "Literals, property references, and a taste of the expression language — then why this repo reads the Environment directly, and why @ConfigurationProperties beats scattered @Value.",
                        text("spel:value", """
                                @Value puts a single resolved value into a single field or parameter. Three escalation levels live inside its argument. LITERALS: @Value("guide") — a constant with extra steps; rare in real code. PROPERTY REFERENCES: @Value("${server.port}") — resolve the named property through the Environment's ordered sources (system properties, environment variables, application.yml), with defaults in the same syntax: ${server.port:8080} answers 8080 when the property is absent. A missing property WITHOUT a default fails startup — fail-fast again, at wiring time. SPEL, the Spring Expression Language: @Value("#{ 2 * 1024 }") evaluates an expression at injection time. Safe, useful SpEL samples: #{ T(java.time.Instant).now() } for a computed constant; #{ someBean.maxSize * 2 } reading ANOTHER bean; #{ ${pool.size} * 2 } — SpEL wrapping a property placeholder, resolved first, multiplied second. SpEL is a real language (property access, method calls, collection projection, type references) — which is why it stays in CONFIG-SHAPED doses: stringly-typed, invisible to the compiler and refactoring tools. Compute a config constant at most; never business logic.

                                This repo reads values a third way, visible in the quote: the Environment is injected ONCE, and getProperty is called in code. Why: the reads are dynamic (per test, per demo), they need null-handling richer than a default literal, and — the architectural reason — ScenarioState.fromEnvironment validates and converts two raw strings into a typed value at ONE place, instead of two @Value fields scattered wherever the store is built. That point generalizes into the rule the environments topic expands: related configuration belongs in ONE @ConfigurationProperties record (typed, validated, documented, metadata-complete in the IDE) rather than a dozen @Value needles across the codebase. @Value earns its place for a lone injected constant; the moment two settings relate to each other, they want a record."""),
                        code("spel:environment", "java",
                                "guide/config/GuideConfig.java — this repo's value resolution: Environment in, typed state out, validation in one place",
                                "server/src/main/java/guide/config/GuideConfig.java", """
                                @Bean
                                ScenarioStore scenarioStore(Environment environment) {
                                    return new ScenarioStore(ScenarioState.fromEnvironment(
                                            environment.getProperty("SCENARIO_DATA_MODE"),
                                            environment.getProperty("SCENARIO_INJECT_FAILURE")));
                                }""")),
                section("circular-dependencies", "Circular dependencies",
                        "A needs B, B needs A: why field/setter circularity 'works', why constructor circularity refuses to start — and why refusing is the feature. Fixes included.",
                        text("circ:story", """
                                Two beans, each needing the other. What happens depends ENTIRELY on the injection style. With FIELD or SETTER injection, the container can instantiate A as a half-built object (constructor runs with nothing), put that early reference into the singleton registry, construct B, inject B into A's field, inject the early A into B — everyone complete, circle closed. It works by design: construction and injection are separate phases, and the gap between them is where the circularity hides. With CONSTRUCTOR injection it cannot: A's constructor needs B, B's constructor needs A, and no fully-usable object exists to hand anyone first. Spring detects the re-entrant creation and fails startup with BeanCurrentlyInCreationException naming both beans. Boot has been tightening this boundary for releases (it now rejects even the field/setter circularity it once papered over — set spring.main.allow-circular-references explicitly if you must), and the direction is a statement: circularity is a design defect the container is allowed to reject.

                                WHY failing fast is the feature: a working early-reference circle is a runtime landmine — both beans were momentarily half-built; initialization-order-sensitive logic may see a not-yet-injected collaborator; and the design smell is the real cost: two objects so entangled neither can be understood, tested, or reused alone. The fixes, in order of preference. RESTRUCTURE — usually the circle is one class doing two jobs: extract the shared piece both need into a third bean; dependency direction should be a DAG, and most "impossible" cycles dissolve on redesign. OBJECTPROVIDER — inject the provider, resolve lazily at use time; breaks the construction deadlock, keeps a visible seam, honest in the bean detail's dependencies. @LAZY — @Lazy on one injection point hands that side a proxy resolved on first use; works, but the circle remains, hidden behind a proxy — reach for it last, on code you cannot change. This repo has zero circles, and the bean detail endpoint makes that CHECKABLE: every dependsOn list you pull resolves to a bean you can pull next, down to the framework leaves."""),
                        code("circ:failure", "bash", "what a constructor circle actually prints — two names, one diagnosis", null, """
                                org.springframework.beans.factory.BeanCurrentlyInCreationException:
                                    Error creating bean with name 'aService':
                                    Requested bean is currently in creation:
                                    Is there an unresolvable circular reference?""")),
                section("terminal-companion", "The container from a terminal",
                        "The beans demo plus the app-info endpoint, driven by curl — including one deliberate 404.",
                        text("term:tour", """
                                The tour worth taking is the container itself. Start with the overview and find totalBeans — a number in the low hundreds, nearly all of it Boot's machinery; the byPackage table is the handful that is OURS. Then drill into one bean by name — showAppInfo is a good first pick: its dependsOn names the beans the container injected into it, and every one of those names is itself a valid next query. Walk the chain down to a bean with an empty dependsOn — a leaf of the object graph, the border where this app meets the framework. Then the deliberate error: a bean name that does not exist returns the typed 404 envelope. Start the stack and take the tour:"""),
                        code("term:curl", "bash", "the curl tour of topic 05", null, """
                                # The whole container, summarized: totals, our packages, sample bean:
                                curl -s localhost:8080/api/demo/core/beans | head -30

                                # The count moves as topics are added — assert ranges, never totals:
                                curl -s localhost:8080/api/demo/core/beans | grep totalBeans

                                # One bean, in full — scope, factory method, dependencies, proxied:
                                curl -s localhost:8080/api/demo/core/beans/showAppInfo

                                # Its dependency, next hop down the object graph:
                                curl -s localhost:8080/api/demo/core/beans/appInfoSource | grep dependsOn

                                # Unknown bean → the typed 404 envelope, requestId and all:
                                curl -i localhost:8080/api/demo/core/beans/no-such-bean

                                # Topic 04's companion view of the same context:
                                curl -s localhost:8080/api/demo/spring/app-info | grep uptimeSeconds

                                # This topic's own content, through the pipeline it teaches:
                                curl -s localhost:8080/api/content/spring-core | head -40""")));
    }
}
