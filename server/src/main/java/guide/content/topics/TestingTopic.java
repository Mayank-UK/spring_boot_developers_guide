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
 * Topic 34 — Testing. Group platform, order 5. The pyramid told honestly,
 * taught from THIS repo's own thousand-test suite: scripted fakes and
 * Mockito where each earns its keep, the modularized Boot 4 slices
 * (@WebMvcTest, @DataJpaTest) against real PostgreSQL through
 * Testcontainers, the awkward parts every suite meets (rollbacks that never
 * commit, shared singletons, clock precision, ordering), the client's
 * black-box vitest discipline, JaCoCo as smoke detector rather than target,
 * the CI contract, and where browser E2E will live — plus a live runner
 * that executes a battery of example checks across the pyramid's layers
 * and reports them the way a real runner does: name, layer, verdict,
 * duration, reason.
 */
public final class TestingTopic {

    private TestingTopic() {
    }

    public static Topic topic() {
        return new Topic("testing", "Testing",
                "The pyramid told honestly: fast units over scripted fakes, "
                        + "thin slices with MockMvc, real PostgreSQL through Testcontainers — "
                        + "and the discipline that keeps a thousand tests trustworthy. This "
                        + "topic quotes the guide's OWN suite, because the app teaching you "
                        + "testing is itself the best example in the repo.",
                TopicGroupId.PLATFORM, 5,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-pyramid-honestly", "The pyramid, honestly",
                        "Many fast unit tests, some slice tests, a few whole-context tests — a budget of confidence, not a diagram to decorate slides.",
                        text("tp:what", """
                                The testing pyramid is a BUDGET, not a diagram. At the bottom, MANY unit tests: one class, no framework, microseconds each — they fail pointed at a method, and you can afford thousands. In the middle, SOME slice tests: one autoconfiguration booted (the MVC machinery, or the JPA machinery) with everything else replaced — milliseconds each, they fail pointed at a seam. At the top, a FEW whole-context tests: the real application, real PostgreSQL, real HTTP — seconds each, they fail pointed at "the app", which is exactly their weakness: maximum honesty, minimum diagnosis.

                                Each layer trades SPEED for CONFIDENCE, and the pyramid is the statement that you cannot buy the apex for everything. This repo is the shape made flesh: the server suite runs well over a thousand tests, the overwhelming majority unit and slice tests that never start a container — the reason a full run takes minutes instead of an hour is that only the tests whose CLAIMS need a real engine pay for one. The JPA probe's query-count assertions? Measured on real PostgreSQL. The bounds-check on a page number? A pure function call, a million times cheaper.

                                What the pyramid is NOT: a law that every class gets a mirror test. Tests are for BEHAVIOR you would regret breaking — the boundary rules, the failure mappings, the concurrency arithmetic. A record that carries data has no behavior to test; a use case that maps a hostile page to a validation failure does, and that test lives at the bottom of the pyramid where it runs in microseconds, quoted in the next section."""),
                        text("tp:cone", """
                                The anti-shape has a name: the ICE CREAM CONE — a fat blob of end-to-end tests on a thin stick of units, usually born from "tests are hard, we'll do them through the UI". It fails three ways at once. It is SLOW, so people stop running it locally and it exists only in CI. It is FLAKY, because it exercises everything including the environment. And it diagnoses NOTHING: a red E2E says "somewhere, something broke" — the finding then needs a unit test that should have existed first. Every bug an E2E catches alone is a bug whose diagnosis you have outsourced to your future self, at panic prices.

                                The debugging direction is the pyramid's real argument: a failing UNIT test points at a method, a failing SLICE test points at a seam, a failing whole-context test points at a haystack. When a defect ships anyway, you add the test at the LOWEST layer that could have caught it — that regression test is cheap forever, which the E2E you actually debugged through never is.

                                Honesty about the top, though: some claims are only true on the real engine. This repo's JPA demo asserts measured query COUNTS (n+1 versus join-fetch), and those numbers are a property of PostgreSQL + Hibernate together — no fake can testify. That is what the apex is FOR, and this is the repo's smallest honest apex, one whole context against a container:"""),
                        code("tp:apex", "java",
                                "server/src/test/java/guide/data/jpa/JpaDemoIntegrationTest.java — the pyramid's apex: the whole context, real PostgreSQL, real HTTP through MockMvc",
                                "server/src/test/java/guide/data/jpa/JpaDemoIntegrationTest.java", """
                                @Testcontainers
                                @SpringBootTest(properties = "spring.grpc.server.port=0")
                                @AutoConfigureMockMvc
                                @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                                class JpaDemoIntegrationTest {

                                    @Container
                                    @ServiceConnection
                                    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

                                    @Autowired
                                    private MockMvc mockMvc;

                                    @Autowired
                                    private JdbcTemplate jdbc;
                                }"""),
                        text("tp:apex-note", """
                                Read the annotations as a cost list. @SpringBootTest: the whole context (every config class, every port wired). @AutoConfigureMockMvc: requests enter through the servlet machinery without a socket. @Testcontainers + @ServiceConnection: a real postgres:17 container whose connection details flow into the autoconfigured DataSource — the test does not know it is a test. One @Order(1) method must run before the live-mode flip — the class javadoc says so and why, which is the discipline the awkward-bits section returns to: ordering is admitted, documented, and rare.""")),
                section("junit-five-and-mockito", "JUnit 5 and Mockito",
                        "Plain classes and honest assertions over scripted fakes; Mockito reserved for surfaces you do not own. This repo's split, and why it works.",
                        text("ju:model", """
                                JUnit 5's model is almost aggressively plain: a test is a method on a class, @Test marks it, and the runner creates a FRESH INSTANCE PER TEST — which is why instance fields in this repo's test classes are fixtures, never shared state. @BeforeEach rebuilds what each test needs; assertions take a MESSAGE that says what the contract was, so a failure reads as the broken promise rather than a bare expected/got diff. The suite's unit tests assert on RESULT VALUES — the Failure kind, the details map — never on how the code under test achieved them. That last rule is what keeps unit tests alive through refactors: PostsUseCasesTest survived the entire Phase 6 swap of the posts repository from in-memory to PostgreSQL because it never once looked at storage.

                                The one design decision worth internalizing: what do you replace the real collaborators WITH? This repo splits the answer in two. Your OWN ports get a hand-rolled SCRIPTED FAKE — a private class implementing the interface whose every answer the test sets like knobs. Framework surfaces you do not own (Spring's Environment, an ApplicationContext) get Mockito. The fake earns its keep because it IS the port contract restated: when the port grows a method, every fake fails to compile and gets fixed — a mock built per-test also notices, but the fake is REUSED, so the contract lives in one place. Here is the posts use case test over its scripted fake:"""),
                        code("ju:scripted-fake", "java",
                                "server/src/test/java/guide/domain/posts/PostsUseCasesTest.java — the scripted fake: the port contract, verbatim, every answer a knob",
                                "server/src/test/java/guide/domain/posts/PostsUseCasesTest.java", """
                                class PostsUseCasesTest {

                                    /** A fake whose every answer the test sets — the port contract, verbatim. */
                                    private static final class ScriptedRepository implements PostRepository {
                                        PagedPosts listAnswer = PagedPosts.of(List.of(), 0, 10, 0);
                                        Optional<Post> findAnswer = Optional.empty();
                                        Post createAnswer;
                                        Update updateAnswer = new NotFound();
                                        boolean deleteAnswer;

                                        // … every port method returns the knob verbatim
                                    }

                                    @Test
                                    void getPostMapsAbsenceToANotFoundFailureCarryingTheId() {
                                        GetPost getPost = new GetPost(repository);
                                        repository.findAnswer = Optional.empty();

                                        Result<Post> result = getPost.execute("missing");
                                        assertTrue(result instanceof Failed<Post>);
                                        Failed<Post> failed = (Failed<Post>) result;
                                        assertEquals(FailureKind.NOT_FOUND, failed.failure().kind());
                                        assertEquals("missing", failed.failure().details().get("postId"));
                                    }
                                }"""),
                        text("ju:mockito", """
                                Note what the test asserts: not "findById was called", but what the MISSING row became — a NOT_FOUND failure whose details carry the id the edge will render. Behavior, not choreography. Interaction assertions (verify, times()) exist in Mockito's toolbox and this suite almost never opens that drawer: verifying call sequences welds the test to the implementation's shape, and the implementation's shape is precisely what refactors change.

                                Mockito's home turf is the seam you did not write. The app-info adapter translates Spring's own Environment and ApplicationContext into plain facts — the unit under test is the BRIDGING, so Spring's surfaces are mocked and the expectations set what each configuration must produce:"""),
                        code("ju:mockito-when", "java",
                                "server/src/test/java/guide/data/spring/AppInfoAdapterTest.java — Mockito where it earns it: surfaces you do not own, scripted by expectations",
                                "server/src/test/java/guide/data/spring/AppInfoAdapterTest.java", """
                                class AppInfoAdapterTest {

                                    private final Environment environment = mock(Environment.class);
                                    private final ApplicationContext context = mock(ApplicationContext.class);

                                    private void noBeans() {
                                        when(context.getBeanDefinitionNames()).thenReturn(new String[0]);
                                        when(context.getBeanNamesForAnnotation(Configuration.class)).thenReturn(new String[0]);
                                    }

                                    @Test
                                    void readsNameProfilesPortAndEndpointsFromTheEnvironment() {
                                        when(environment.getProperty("spring.application.name", "unknown"))
                                                .thenReturn("springboot-developers-guide");
                                        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
                                        when(environment.getProperty("server.port", Integer.class)).thenReturn(8080);
                                        // … the exposure property and the no-beans baseline

                                        AppInfoFacts facts = new AppInfoAdapter(environment, context, null).facts();

                                        assertEquals("springboot-developers-guide", facts.applicationName());
                                    }
                                }"""),
                        text("ju:choice", """
                                The split, stated as a rule you can apply tomorrow: FAKE what you own (a port you can implement in twenty lines, reused across tests, kept honest by the compiler); MOCK what you do not (a framework class you cannot reimplement, scripted per test because each test wants a different configuration of it). Both beat the third option — spinning the real dependency up — which is exactly what slice tests are for, and which is why you should never pay it inside a unit test.""")),
                section("the-webmvc-slice", "The @WebMvcTest slice",
                        "One controller, the real servlet machinery, every collaborator replaced — the HTTP contract tested in milliseconds. Plus this topic's live runner.",
                        text("sl:what", """
                                @WebMvcTest(ContentController.class) boots ONE controller plus the MVC autoconfiguration around it — argument resolvers, converters, the @ControllerAdvice, the filters you registered — and NOTHING else. No services, no repositories, no scenario store. The controller's collaborators arrive as @MockitoBean: Mockito-created stand-ins Spring injects in place of the real beans, their answers set per test. What you get is the controller's CONTRACT exercised at the HTTP edge: parse the request, call the use case ONCE with the parsed value, map the Result onto status + body. MockMvc then performs real requests through the servlet machinery in-process — no socket, but routing, binding, serialization, and the error advice all run for real:

                                The Boot 4 fine print this repo paid for already, so you do not: the slice annotations moved OUT of spring-boot-starter-test into per-technology artifacts — @WebMvcTest lives in spring-boot-webmvc-test, @DataJpaTest in spring-boot-starter-data-jpa-test (the pom block in the next section shows both). Omit the artifact and the tests silently do not exist. And a quiet blessing of the modularization: the slice does NOT import the security autoconfiguration, so slices stay untouched when Spring Security lands — security's own tests run against the whole context instead."""),
                        code("sl:slice", "java",
                                "server/src/test/java/guide/presentation/ContentControllerTest.java — the slice pattern: MockMvc in, jsonPath out, use cases as @MockitoBean",
                                "server/src/test/java/guide/presentation/ContentControllerTest.java", """
                                @WebMvcTest(ContentController.class)
                                class ContentControllerTest {

                                    @Autowired
                                    private MockMvc mockMvc;

                                    @MockitoBean
                                    private ListTopicSummaries listTopicSummaries;

                                    @MockitoBean
                                    private GetTopicById getTopicById;

                                    // … a hand-built Topic fixture with all three block kinds

                                    @Test
                                    void listServesVersionGroupsAndSummaries() throws Exception {
                                        TopicList payload = TopicList.of(
                                                List.of(guide.content.TopicSummary.from(topic())));
                                        when(listTopicSummaries.execute()).thenReturn(Result.success(payload));

                                        mockMvc.perform(get("/api/content"))
                                                .andExpect(status().isOk())
                                                .andExpect(jsonPath("$.version").value(1))
                                                .andExpect(jsonPath("$.groups.length()").value(10))
                                                .andExpect(jsonPath("$.topics[0].id").value("spring-core"))
                                                .andExpect(jsonPath("$.topics[0].sections").doesNotExist());
                                    }

                                    // … and the 404 path: a Failed Result renders the error envelope
                                    mockMvc.perform(get("/api/content/nope"))
                                            .andExpect(status().isNotFound())
                                            .andExpect(jsonPath("$.error.code").value("not_found"))
                                            .andExpect(jsonPath("$.error.requestId").isNotEmpty())"""),
                        text("sl:assertions", """
                                Everything asserted there is wire contract: the status, the version field, the group count, the ABSENCE of sections in a summary. jsonPath reads the parsed JSON, so a test that passes here means the bytes serialize the way the client's parser expects — one notch more honest than asserting on a returned object. And the 404 path is where the slice quietly tests the ERROR TAXONOMY: a Failed Result becomes the shared envelope with a code and a live requestId — topic 30's whole machinery, exercised in a five-millisecond test that never starts a server.

                                The same discipline drives this topic's live demo. The runner below executes a small battery of example checks across the pyramid's layers — pure unit rules, a data-layer read through the wired repository, a web-layer self-call over real HTTP — and reports each the way a test runner does: name, layer, PASSED/FAILED/SKIPPED, duration, and the reason. Flip the scenario's failure injection and one deliberately wrong assertion goes red, because a runner you have only ever seen green teaches nothing:"""),
                        demo("sl:runner-demo", "testing:runner", "The test runner, live",
                                "Run a battery of example checks across the pyramid's layers — pure unit rules, a data-layer paged read, a web-layer self-call — and read the runner's report: every check with its verdict, duration, and reason. Flip the failure-injection switch to see what a deliberately wrong assertion looks like when it fails."),
                        code("sl:runner-java", "java",
                                "guide/domain/testing/RunTestBattery.java — the battery as a use case: outcome first, duration around it, surprises become failures",
                                "server/src/main/java/guide/domain/testing/RunTestBattery.java", """
                                public BatteryReport execute() {
                                    long startNs = System.nanoTime();
                                    boolean injected = scenario.get().injectFailure();
                                    List<CheckResult> checks = List.of(
                                            timed("unit: bounds — a hostile page is a validation failure", "unit", this::checkBounds),
                                            timed("unit: absence — an unknown id maps to not_found carrying the id", "unit", this::checkAbsence),
                                            timed("data: a paged read serves titled posts within the page bound", "data", this::checkPagedRead),
                                            timed("web: a self-call answers the scenario endpoint", "web", this::checkSelfCall),
                                            injected
                                                    ? timed(INJECTED_CHECK, "injected", this::checkInjectedFailure)
                                                    : new CheckResult(INJECTED_CHECK, "injected", SKIPPED, 0.0,
                                                            "failure injection is off — flip the scenario switch to see a check fail"));
                                    // … the tally counts each status; the report carries the scenario it ran under
                                }

                                private CheckResult timed(String name, String layer, Supplier<Outcome> check) {
                                    long startNs = System.nanoTime();
                                    Outcome outcome;
                                    try {
                                        outcome = check.get();
                                    } catch (RuntimeException unexpected) {
                                        outcome = Outcome.failed("threw " + unexpected.getClass().getSimpleName()
                                                + ": " + unexpected.getMessage());
                                    }
                                    return new CheckResult(name, layer, outcome.status(),
                                            (System.nanoTime() - startNs) / 1_000_000.0, outcome.detail());
                                }"""),
                        code("sl:runner-ts", "tsx",
                                "client/src/ui/demos/testing/testing_runner_demo.tsx — the report as a live surface: run, render every verdict, PATCH the injection switch",
                                "client/src/ui/demos/testing/testing_runner_demo.tsx", """
                                const run = useCallback(() => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () =>
                                    parseReport((await guardedGetJson(config, '/demo/testing/run')).body),
                                  ).then((result) => {
                                    if (result.ok) {
                                      setReport(result.value);
                                      setPanel({ kind: 'idle' });
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                }, [config]);

                                /** Flip the scenario store the way every failure-injection demo does: one PATCH, shared state. */
                                const toggleInjection = (next: boolean) => {
                                  setInject(next);
                                  void safeRepositoryCall(async () => {
                                    const response = await fetch(`${config.apiBaseUrl}/demo/scenario`, {
                                      method: 'PATCH',
                                      headers: { 'content-type': 'application/json', accept: 'application/json' },
                                      body: JSON.stringify({ injectFailure: next }),
                                    });
                                    if (!response.ok) {
                                      throw new Error(`the scenario patch failed with status ${String(response.status)}`);
                                    }
                                    return undefined;
                                  }).then((result) => {
                                    if (result.ok) {
                                      run();
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                };
                                """),
                        text("sl:runner-note", """
                                Two honest details in that runner. The web check reports SKIPPED when its machinery is absent (a MockMvc slice has no live server to self-call) — the same degrade-gracefully rule every optional service follows, applied to a check instead of an endpoint: machinery missing is a finding to REPORT, never a verdict to fake. And the data check asserts the page BOUND and row shape rather than an exact count — the seed's size is the substrate's business (live mode adds and deletes rows), so the assertion stays true against either backing store. Both are the section's lesson wearing a runner's clothes: assert behavior, report absence, never fabricate a verdict.""")),
                section("datajpatest-and-testcontainers", "@DataJpaTest and Testcontainers",
                        "Repository-shaped tests against REAL PostgreSQL — no H2, no dialect surprises — with the slice's rollback discipline and the Boot 4 artifact map.",
                        text("dj:why-real", """
                                The project decision that shapes this whole section: PostgreSQL-ONLY, never H2. The reasoning is dialect honesty — the entities USE dialect features (a text[] column converter, CHECK constraints, an advisory-lock race lab), and every embedded-database swap tests a database you do not run: green in CI, mysterious in prod. Testcontainers closes the gap by giving the test the REAL thing: @Container + @ServiceConnection boots a postgres:17 container and feeds its connection details straight into the autoconfigured DataSource. The slice never knows the difference — which is the point.

                                @DataJpaTest scopes the context to the JPA machinery: repositories, an EntityManager, transaction ROLLBACK after every test. Two Boot 4 fine prints this suite documents in place. First, replace = NONE — without it the slice would helpfully swap your PostgreSQL for an embedded database, defeating the entire decision. Second, the Boot 4 slice's autoconfigure set NO LONGER RUNS FLYWAY — so this repo pays the schema bill the app's way: SchemaMigrator, programmatically, idempotently, in @BeforeEach:"""),
                        code("dj:slice", "java",
                                "server/src/test/java/guide/data/jpa/PostEntityRepositoryTest.java — the @DataJpaTest slice: real PG via @ServiceConnection, schema migrated the app's way",
                                "server/src/test/java/guide/data/jpa/PostEntityRepositoryTest.java", """
                                @Testcontainers
                                @DataJpaTest
                                @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
                                class PostEntityRepositoryTest {

                                    @Container
                                    @ServiceConnection
                                    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

                                    @Autowired
                                    private PostEntityRepository posts;

                                    // … the EntityManager and DataSource the proofs below need

                                    @BeforeEach
                                    void migrateSchemaAndCleanSlates() {
                                        Result<SchemaMigrator.MigrationSummary> migrated = new SchemaMigrator(dataSource).migrate();
                                        if (migrated instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
                                            throw new IllegalStateException("slice schema migration failed: " + failed.failure().message());
                                        }
                                        posts.deleteAll();
                                        entityManager.createNativeQuery("delete from audit_log").executeUpdate();
                                    }
                                }"""),
                        text("dj:artifacts", """
                                The artifact map, because Boot 4 will not guess it for you: spring-boot-starter-test is the BASE (JUnit, Mockito, AssertJ, JSONPath); spring-boot-webmvc-test carries @WebMvcTest; spring-boot-starter-data-jpa-test carries @DataJpaTest. The Testcontainers side renames EVERYTHING in its 2.x line — the classic junit-jupiter and postgresql artifacts became testcontainers-junit-jupiter and testcontainers-postgresql, versionless because the Boot 4.1.1 BOM imports testcontainers-bom 2.0.5; the class API is unchanged. spring-boot-testcontainers is what makes @ServiceConnection work, so a container FIELD feeds the autoconfigured beans with zero wiring. One operational note for Docker Desktop: the Ryuk cleanup container needs TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal when your tests themselves run inside a container — the repo's verification command ships it:"""),
                        code("dj:pom", "xml",
                                "server/pom.xml — the test stack: the base starter, the two slice artifacts, the renamed Testcontainers trio",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                                <!-- Spring Boot 4 modularized test slices: @WebMvcTest lives here, no longer in
                                     spring-boot-test-autoconfigure (classic spring-boot-starter-test). -->
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-webmvc-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                                <!-- … -->
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-testcontainers</artifactId>
                                    <scope>test</scope>
                                </dependency>
                                <dependency>
                                    <groupId>org.testcontainers</groupId>
                                    <artifactId>testcontainers-junit-jupiter</artifactId>
                                    <scope>test</scope>
                                </dependency>
                                <dependency>
                                    <groupId>org.testcontainers</groupId>
                                    <artifactId>testcontainers-postgresql</artifactId>
                                    <scope>test</scope>
                                </dependency>"""),
                        text("dj:cost", """
                                The cost accounting for a container-per-suite: the FIRST boot pulls and starts postgres:17 (seconds); Ryuk reaps it when the JVM exits; and the static @Container field means every test CLASS shares one container, not one per test — the @BeforeEach cleans SLATES, not engines. That sharing is why this repo's whole data suite stays inside a couple of minutes. The trade you accept for real-engine honesty: the suite needs a Docker daemon, CI runners have one, and a colleague without Docker simply cannot run those tests — the suite README says so rather than pretending.""")),
                section("the-awkward-bits", "The awkward bits",
                        "Rollbacks that never commit, shared singletons, clock precision, ordering, flake discipline — the parts every real suite meets and the honest answers this one gives.",
                        text("aw:list", """
                                Every suite eventually meets the parts the tutorials skip. ASYNC: a fire-and-forget worker offers nothing to await — this repo polls with a bounded settle window (the trace probe waits up to two seconds for finished spans, then reports what it has) because an UNBOUNDED wait is how a test suite becomes a flake generator. TIME: never assert on now() — inject the clock (the webhook simulator's backoff runs on a fake clock, so a six-attempt retry schedule is tested in microseconds, wall-clock-free). RANDOMNESS: seed it or bound it (a UUID collision "test" asserts the v7 ordering PROPERTY, not luck). Each fix is the same move: replace an ambient dependency with an injected one, then the test controls it.

                                SHARED SINGLETONS: the observability ring is one appender for the whole context, so tests that emit and then read CANNOT assume they are alone on it — the slice test asserts the FILTER's contract (every event in a WARN-filtered answer is WARN, at least one exists) instead of exact counts, and says why in a comment. CLOCK PRECISION: two rows stamped in the same millisecond make every createdAt-descending assertion a coin flip — the JPA slice staggers its fixture history by explicit seconds for exactly that reason.

                                The subtlest awkward bit is TRANSACTIONAL VISIBILITY: the @DataJpaTest slice wraps every test in a transaction that NEVER COMMITS — perfect isolation, until the claim under test IS a committed race. Read-committed means a second EntityManager cannot see the test's uncommitted rows, so the optimistic-locking proof plays the OTHER writer as a native UPDATE inside the test's own transaction:"""),
                        code("aw:rollbacks", "java",
                                "server/src/test/java/guide/data/jpa/PostEntityRepositoryTest.java — when the slice's rollback hides the race, play the other writer natively",
                                "server/src/test/java/guide/data/jpa/PostEntityRepositoryTest.java", """
                                // The slice's test transaction never commits, so a second EntityManager
                                // cannot see these rows (read-committed). The stale-writer proof runs
                                // INSTEAD against a native UPDATE that plays the OTHER writer — the
                                // exact WHERE version = ? predicate topic 17 wrote by hand. ORDER
                                // MATTERS: the native update runs FIRST (nothing dirty pending, so no
                                // auto-flush races it), bumping the row to version 2 behind the
                                // persistence context's back; only then does our instance go dirty.
                                PostEntity staleWriter = posts.findById(id).orElseThrow();
                                int otherWriterRows = entityManager.createNativeQuery(
                                                "update posts set version = 2, title = ? where id = ? and version = 1")
                                        .setParameter(1, "race — other writer won (native)")
                                        .setParameter(2, id)
                                        .executeUpdate();
                                assertEquals(1, otherWriterRows, "the other writer's version-1 update matched exactly one row");
                                """),
                        text("aw:ordering-flakes", """
                                ORDERING is the one admission this suite makes and labels: JpaDemoIntegrationTest orders its methods because the fake-mode assertion MUST precede the live-mode flip — a state transition under test, documented in the class javadoc, reviewed like any other decision. Everywhere else, order-independence is the rule, and @BeforeEach does the resetting. The moment you need ordering to make ORDINARY tests pass, you have a fixture leaking state — fix the leak.

                                FLAKE DISCIPLINE, the repo's working rules: a flaky test is a bug with better PR (it gets an issue and a fix, not a rerun button — this suite carries exactly ONE deliberate skip, and it asserts a known limitation openly rather than pretending); a transient failure under parallel load earns ONE rerun before diagnosis; and root-causes get written down next to the test (the JWT tamper test's one-in-six signature-character collision is explained in the backlog, which is how the NEXT person fixes it instead of re-deriving it).

                                And the discipline that polices all the others — MUTATION BY HAND: before trusting a test, break the code it claims to cover (flip an operator, delete a guard) and watch it go red. A test that stays green was decoration. The runner demo above invites exactly that: flip the scenario's injection switch and the wrong assertion fails, visibly, with its reason — the smallest possible rehearsal of the move you should perform on your own suite.""")),
                section("client-testing", "Client testing",
                        "Vitest and React Testing Library, black-box through stubbed fetch: drive the DOM, assert what a user sees — including the degraded server.",
                        text("cl:model", """
                                The client suite (sixty-odd spec files, several hundred tests) tests demos the way a USER meets them: render the component, interact with the DOM, assert on what appeared. Vitest provides the runner and the assertion grammar; React Testing Library provides the rendering and the query discipline (findByTestId for async appearance, fireEvent for interaction); and the whole outer boundary is stubbed at window.fetch — one vi.stubGlobal per spec, serving canned JSON exactly as the wire would. Because the stub answers at the transport level, the ENTIRE pipeline under it runs for real: the guarded fetch, the envelope-to-Failure mapping, the strict DTO parsers that throw on malformed shapes. A spec for this topic's own runner demo shows the shape:"""),
                        code("cl:spec", "tsx",
                                "client/src/ui/demos/testing/testing_runner_demo.spec.tsx — the black-box spec: stubbed wire, driven DOM, asserted verdicts",
                                "client/src/ui/demos/testing/testing_runner_demo.spec.tsx", """
                                it('runs the battery and renders every check with verdict and reason', async () => {
                                  const captured: Captured[] = [];
                                  stubEndpoints(captured, 'green');

                                  render(<TestingRunnerDemo />);
                                  fireEvent.click(screen.getByTestId('testing-run'));

                                  const checks = await screen.findByTestId('testing-checks');
                                  expect(checks).toHaveTextContent('PASSED');
                                });
                                """),
                        text("cl:discipline", """
                                Three habits make those specs trustworthy. The container is initialized in beforeAll with a real app config — components resolve their dependencies through it, so the spec exercises the same wiring production does. Stubs are removed in afterEach (vi.unstubAllGlobals) — a leaked stub is a cross-test infection waiting for a victim. And every demo spec includes the DEGRADED path: the stub serves a 503 envelope and the test asserts the error panel renders the server's own message — the client's Result discipline (loading / ready / error panels) is testable for the same reason it is renderable, because failure is a state, not a crash.

                                What the client suite deliberately does NOT do: snapshot testing (a changed class name fails a snapshot while breaking nothing), implementation queries (component internals are invisible), and mocking the data layer's modules (the fetch boundary is the seam; above it everything runs, below it nothing exists). The result is specs that read like a user's checklist and fail like a user's complaint.""")),
                section("jacoco-coverage-honestly", "JaCoCo, honestly",
                        "Bytecode coverage as a smoke detector — not a target. What the tool measures, what it cannot, and why this repo defers the gates.",
                        text("jc:what", """
                                JaCoCo instruments compiled bytecode and records which lines and BRANCHES executed while tests ran. That is the whole trick, and its whole limitation: it measures EXECUTION, not ASSERTION — a test that calls everything and checks nothing reports 100%. Run it without touching the build (no pom changes needed — fully-qualified plugin goals work straight from the CLI):
                                """),
                        code("jc:run", "bash",
                                "coverage, ad hoc: agent + report, then read the HTML",
                                null, """
                                # inside the server container (the repo's verification mount):
                                mvn -q org.jacoco:jacoco-maven-plugin:prepare-agent test \\
                                    org.jacoco:jacoco-maven-plugin:report

                                open server/target/site/jacoco/index.html   # line + branch coverage, per package
                                # the xml report (jacoco.xml) is what a CI gate or a PR comment would consume"""),
                        text("jc:honestly", """
                                How to read the number: as a SMOKE DETECTOR, never a target. Low coverage on a package you believe is tested is a genuine finding — usually untested error paths, which is where defects live. Chasing a percentage is Goodhart's law with a badge: tests get written to touch lines, assertions thin out, and the metric rises as the suite weakens. The two numbers that matter more than the total: branch coverage on the FAILURE paths (the taxonomy mapping, the guards), and what the MISSED list tells you about risk.

                                This repo's own decision, stated for the record: coverage GATES are deferred to the hardening phase, deliberately. A teaching repo's content files and demos carry long stretches where the "coverage" question is not meaningful (prose-bearing records, hand-driven browser surfaces), and bolting a gate on early would optimize the suite toward the metric before the suite exists. The honest order is the one this topic built: tests that assert behavior first, the measurement second, the gate last — and only where it guards a real regression risk.""")),
                section("the-ci-contract", "The CI contract",
                        "Green on a clean machine, with the same commands the developer runs — that is the entire job description. This repo's workflow, read as a contract.",
                        text("ci:what", """
                                CI is not a linting ritual; it is a CONTRACT: the branch is shippable if and only if the same commands that verify it locally are green on a machine nobody pre-warmed. That definition does the design work — same commands (no CI-only flags that test a different thing), clean machine (dependencies come from the lockfile and the cache, not from a developer's warm volume), and both packages (server AND client, because the guide is the pair of them). The workflow is small enough to read in one glance, which is itself the standard:"""),
                        code("ci:workflow", "yaml",
                                ".github/workflows/ci.yml — the whole contract: two jobs, the same commands the devcontainer runs",
                                ".github/workflows/ci.yml", """
                                jobs:
                                  server:
                                    runs-on: ubuntu-latest
                                    defaults:
                                      run:
                                        working-directory: server
                                    steps:
                                      - uses: actions/checkout@v4
                                      - uses: actions/setup-java@v4
                                        with:
                                          distribution: temurin
                                          java-version: 25
                                          cache: maven
                                          cache-dependency-path: server/pom.xml
                                      - run: mvn -B test
                                """),
                        text("ci:notes", """
                                The details that make it honest: the server job needs no service containers because the suite's Testcontainers boots its own postgres (the runner's Docker daemon is the only prerequisite ubuntu-latest provides for free); the caches are keyed by the inputs that invalidate them (the pom for Maven, the lockfile for npm) so a dependency bump pays exactly one cold run; and the client job ends with npm run build because a type error that survives the tests must still die in CI — build is part of the contract, not a bonus.

                                The discipline on the human side, briefly: RED MEANS STOP. A red main is a broken contract for every person who pulls it — fix or revert, never skip-a-test-to-go-green (a skipped test is an admission, and admissions get issue numbers). And keep the suite fast enough that developers run it — a suite too slow to run locally stops being a tool and becomes a queue.""")),
                section("browser-e2e", "Browser E2E",
                        "The pyramid's tip: a real browser driving the real stack, for the few journeys whose truth is the whole system. What it costs, and where this repo's suite will live.",
                        text("e2e:what", """
                                Some truths only exist in a real browser: the rendered layout, the login flow across storage and redirects, the drag that no component test simulates honestly. End-to-end tests buy exactly those truths — a real browser (Chromium, via Playwright) clicking through the REAL stack: server on 8080 with its containers up, client on its port, nothing stubbed anywhere. In exchange they are the slowest, flakiest, least diagnosing tests you can write, which is why the pyramid allots them a TIP, not a tier: one per critical JOURNEY (a reader opens the guide and reads a topic; the failure-injection switch visibly reddens a check), not one per feature.

                                This repo's e2e suite does not exist yet, and this guide does not fabricate code it cannot quote — it is planned for the hardening phase, host-run (a Playwright spec needs a real browser process, which the in-container verification mounts deliberately do not provide) in an e2e/ directory beside server/ and client/. The shape it will take, honestly sketched as commands rather than pretend-source:
                                """),
                        code("e2e:sketch", "bash",
                                "the planned suite, sketched where every e2e effort starts",
                                null, """
                                npm init playwright@latest          # e2e/ — chromium only, webServer config not needed:
                                                                   # the compose stack already serves both ports

                                # e2e/topic.spec.ts (future): a reader's journey, for real
                                #   await page.goto('http://localhost:5176/');
                                #   click a topic · expect its sections to render · flip a demo switch
                                #   · expect the failing check to appear — DOM to database, nothing stubbed

                                npx playwright test                 # headless chromium against the running stack"""),
                        text("e2e:cost", """
                                The cost honesty before you write your own: browser suites fail for ENVIRONMENT reasons (port already taken, container still booting, animation mid-frame) more often than for code reasons, so every e2e test earns its keep twice — once as a check, once as a stable-environment engineer. The mitigations that actually work: wait for OBSERVABLE state (a rendered test id, never a sleep), boot the stack once per run (not per test), and keep the count small enough that the whole suite is still faster than a coffee. When an e2e test flakes, the same rule as everywhere else applies: it is a bug with better PR — root-cause it or delete it.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic, curled: the runner's report, the injected red check, the suite commands, and a first coverage read.",
                        text("tc:intro", """
                                Every screen in this topic has a terminal twin. The tour assumes the stack up (docker compose -f .docker/docker-compose.yml up) with the server on localhost:8080. The suite commands assume the repo's container verification mounts — the same ones CI runs, which is the contract in one line."""),
                        code("tc:runner", "bash",
                                "the runner's report, then the injected red check",
                                null, """
                                curl -s localhost:8080/api/demo/testing/run | head -c 700
                                # {"failureInjected":false,"passed":3,"failed":0,"skipped":2,
                                #  "totalDurationMs":3.1,"checks":[
                                #   {"name":"unit: bounds — a hostile page is a validation failure",
                                #    "layer":"unit","status":"PASSED","durationMs":0.2, …}
                                #   {"name":"web: a self-call answers the scenario endpoint",
                                #    "layer":"web","status":"SKIPPED", …}]}

                                # flip the switch, run again — the wrong assertion goes red, by name
                                curl -s -X PATCH -H 'content-type: application/json' \\
                                     -d '{"injectFailure":true}' localhost:8080/api/demo/scenario
                                curl -s localhost:8080/api/demo/testing/run | grep -o '"failed":[0-9]*'   # "failed":1"""),
                        text("tc:suite-note", """
                                The suite itself — the commands this topic's CI section calls the contract, exactly as the repo verifies them (flock because parallel runs share the mount; the socket and HOST_OVERRIDE because the Testcontainers tests spawn sibling containers):"""),
                        code("tc:suite", "bash",
                                "the whole suite, both packages, the CI way",
                                null, """
                                # server — a thousand-plus tests, real PostgreSQL through Testcontainers
                                docker run --rm -v "$PWD":/repo -w /repo/server -v sbdg-m2:/root/.m2 \\
                                     -v /var/run/docker.sock:/var/run/docker.sock \\
                                     -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \\
                                     maven:3.9-eclipse-temurin-25 \\
                                     bash -lc 'flock /repo/server/.verify.lock mvn -q test'

                                # client — vitest, black-box through stubbed fetch
                                docker run --rm -v "$PWD/client":/app -w /app -v sbdg-npm:/root/.npm \\
                                     node:22-bookworm bash -lc 'flock /app/.verify.lock bash -c "npm ci && npm test"'"""),
                        text("tc:close", """
                                And the coverage first read, from the JaCoCo section, worth running once so the numbers stop being abstract: the agent-and-report pair in that section's bash block, then open server/target/site/jacoco/index.html and go straight to the MISSED branches in the error taxonomy — that is the list this entire topic has been teaching you how to shrink.""")));
    }
}
