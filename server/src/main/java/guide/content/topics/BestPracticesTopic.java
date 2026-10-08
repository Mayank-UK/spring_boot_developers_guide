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
 * Topic 42 — Best Practices. Group platform, order 13. The capstone
 * synthesis: forty-one topics taught mechanisms; this one collects the
 * JUDGMENTS that decided between them, and every judgment points at real
 * code already in this repo — the sourced snippets are the codebase's own
 * best exemplars, not illustrations written for the occasion. The live demo
 * (best:checklist) turns the whole topic into a checklist the RUNNING app
 * grades against itself: live verdicts read from in-process state (the
 * registered failure taxonomy, the validated content registry, the
 * effective configuration), curl pointers where truth is environmental —
 * the honesty that a checklist which always says PASS is worth nothing.
 */
public final class BestPracticesTopic {

    private BestPracticesTopic() {
    }

    public static Topic topic() {
        return new Topic("best-practices", "Best Practices",
                "The capstone: every practice this guide teaches, consolidated into one "
                        + "checklist — and every one of them POINTS AT code already running in "
                        + "this repo. Typed configuration that fails the boot, one error taxonomy, "
                        + "hash-and-verify credentials, SQL that binds every value, resilience "
                        + "behind a port, logs as fields, measurements over guesses, graceful "
                        + "shutdown, CI as the definition of done — capped by the anti-checklist "
                        + "demo, where the app grades ITSELF live and hands you a curl for "
                        + "everything it cannot see.",
                TopicGroupId.PLATFORM, 13,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("setup", "Setup: decisions that age well",
                        "Most of a service's future pain is decided in its first week. The setup choices this repo made — one BOM, one typed config record, twelve-factor overrides — are the ones that still pay.",
                        text("bp:setup-intro", """
                                BEST PRACTICES TOPICS ARE UNFAIR. Every rule sounds obvious read aloud — validate your configuration, manage your versions, don't invent a second error format — and every one of them exists because a real codebase skipped it and paid. So this topic refuses to preach from a whiteboard: each section names the practice, explains the judgment behind it, and then QUOTES THIS REPO'S OWN CODE as the exemplar — the snippet sources are files that compile, run, and are tested every CI push. The guide you just read taught forty-one mechanisms; this topic is the layer above them, the taste that decides which mechanism to reach for.

                                START WHERE THE PROJECT STARTED, because setup decisions compound the longest. Version management: the pom pins ONE thing — the Spring Boot parent — and every starter under it rides versionless through the BOM. That is not laziness; it is the dependency-management topic's whole lesson compressed into a file header. The moment you hand-pin a managed dependency you own its upgrade forever; the moment you add a second framework BOM (this repo has exactly one, for spring-grpc) you align a family, not a version:
                                """),
                        code("bp:pom-parent", "xml",
                                "server/pom.xml — the whole version story in one parent: starters below this are versionless, upgrades arrive as one number",
                                "server/pom.xml", """
                                <parent>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-parent</artifactId>
                                    <version>4.1.1</version>
                                    <relativePath/>
                                </parent>"""),
                        text("bp:setup-config", """
                                The second setup decision: CONFIGURATION IS TYPED, VALIDATED, AND FAILS THE BOOT. The environments topic built the pattern and the whole app lives by it — every guide.env.* setting binds into one constructor-bound record whose compact constructor normalizes and rejects. The rule it encodes is worth restating exactly because it is uncomfortable: an invalid config value must fail STARTUP, never the first request that trips over it. A boot that fails is a deployment event — visible, rollback-able, at 3 a.m. or 3 p.m. equally. A request that fails is a USER event, discovered by a user. The compact constructor is three lines of judgment:
                                """),
                        code("bp:app-config", "java",
                                "guide/core/AppConfig.java — invalid configuration fails the BOOT, not the first request: the closed set is checked in the record's constructor",
                                "server/src/main/java/guide/core/AppConfig.java", """
                                public AppConfig {
                                    deployEnvironment = deployEnvironment == null || deployEnvironment.isBlank()
                                            ? "local"
                                            : deployEnvironment.trim().toLowerCase(Locale.ROOT);
                                    if (!KNOWN_ENVIRONMENTS.contains(deployEnvironment)) {
                                        throw new IllegalStateException("Unknown guide.env.deploy-environment \\"" + deployEnvironment
                                                + "\\" — expected one of " + KNOWN_ENVIRONMENTS
                                                + " (invalid config fails the boot, not the first request)");
                                    }
                                    // …
                                }"""),
                        text("bp:setup-close", """
                                And the third: TWELVE-FACTOR OVERRIDES ON EVERYTHING. Look at any line in application.yml and you find ${ENV_VAR:default} — the same file runs local, in compose, and in k8s with different environments purely through environment variables. The payoff shows up in the deployment topic's one-build-many-envs proof: the artifact never changes between environments, so "works in staging" and "works in prod" differ by configuration you can READ, not by a rebuild you can hope about. That trio — one BOM, boot-failing typed config, env-overridable everything — is the setup floor. Everything else in this topic is built on it.""")),
                section("api-discipline", "API discipline",
                        "An API is a promise made once and kept forever. The practices here — typed use cases, one error taxonomy, thin controllers — are how this repo keeps the promise mechanical instead of aspirational.",
                        text("bp:api-intro", """
                                THE FIRST DISCIPLINE: BUSINESS LOGIC LIVES IN TYPED USE CASES, ONE OPERATION PER CLASS, SPEAKING IN RESULTS. The api-design topic built the posts substrate this way and every domain area since has copied the shape. A use case is a small public class with one execute method; its inputs are already-judged values (a PostDraft, not raw strings); its output is a Result — success carries the value, failure carries a typed Failure, and NOTHING THROWS past this boundary for expected failures. Why this is a best practice rather than a style: one operation per class makes the operation NAMEABLE — in reviews ("does CreatePost allow that?"), in tests (one class, one behavior to pin), in on-call ("the create path is slow"). The use case below is the entire create operation; read it and you have read the feature:
                                """),
                        code("bp:typed-use-case", "java",
                                "guide/domain/posts/CreatePost.java — one operation, one class, failures as values: the whole feature is readable in seven lines",
                                "server/src/main/java/guide/domain/posts/CreatePost.java", """
                                public Result<Post> execute(PostDraft draft) {
                                    if (draft == null) {
                                        return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                "a creation needs a draft (title, body)", null));
                                    }
                                    return Result.success(repository.create(draft));
                                }"""),
                        text("bp:api-envelope", """
                                THE SECOND DISCIPLINE: ONE ERROR TAXONOMY, END TO END. The exception-handling topic built it and nothing since has been allowed to invent a second format: every failure the app can produce is one of eight kinds, each carrying its HTTP status and machine-readable code IN THE ENUM ITSELF — the single place those mappings live. Layers below HTTP speak only in Failure values; the @ControllerAdvice renders them into the one envelope {error:{code,message,requestId,…details}}. The reason this is load-bearing is arithmetic: one taxonomy means clients write ONE parser; two taxonomies means every client has two parsers and a guess. The cost of a stray 500-with-a-stack-trace is paid by whoever integrates with you, forever:
                                """),
                        code("bp:failure-kind", "java",
                                "guide/core/errors/FailureKind.java — one enum, eight kinds, the only place status codes and wire codes are invented",
                                "server/src/main/java/guide/core/errors/FailureKind.java", """
                                public enum FailureKind {
                                    VALIDATION(400, "validation_failed"),
                                    UNAUTHORIZED(401, "unauthorized"),
                                    // …
                                    RATE_LIMITED(429, "rate_limited"),
                                    UNAVAILABLE(503, "service_unavailable"),
                                    INTERNAL(500, "internal_error");"""),
                        text("bp:api-close", """
                                THE THIRD: THIN CONTROLLERS, enforced by habit and by the architecture test. Parse and validate input at the edge, call ONE use case, map the Result onto HTTP — no branching on business conditions, no repository calls, no string assembly that belongs in the domain. When the dependency-rule test scans imports, controllers that reached into the data layer fail the BUILD; the review habit and the mechanical check back each other up. And beside all three: VALIDATION AT THE BOUNDARY, ONCE, in the shape of PostDraft.of — a hostile body becomes a typed 400 envelope with per-field reasons at the door, so no layer deeper than the door ever re-litigates whether a title is 120 characters. Discipline you can see in the file list; that is the test.""")),
                section("security", "Security: the boundary habits",
                        "Security in this repo is not a module — it is boundary habits applied everywhere. Two of them carry most of the weight, and both live in one small use case.",
                        text("bp:sec-intro", """
                                THE SECURITY TOPICS (23–25) BUILT THE MACHINERY — filter chains, RBAC, rate limits, headers. The best practices are smaller and travel further. First: CREDENTIALS ARE HASHED AND VERIFIED, NEVER STORED, NEVER LOGGED. The single credential check every login-shaped endpoint funnels through is one use case, and its two deliberate details ARE the curriculum. The failure is one shape for "no such user" and "wrong password" — an API that answers differently for unknown users hands out a USERNAME ORACLE to anyone who can spell curl. And the encoder is the injected PasswordEncoder — the same bean the framework's own provider uses, so the login path and this use case can never disagree about hash format. The verification is matches(raw, storedHash) — the stored hash never becomes a password again:
                                """),
                        code("bp:authn", "java",
                                "guide/domain/auth/AuthenticateCredentials.java — hash-and-verify with one failure shape: no username oracle, no second hash format",
                                "server/src/main/java/guide/domain/auth/AuthenticateCredentials.java", """
                                return identityStore.findByUsername(username)
                                        .filter(user -> passwordEncoder.matches(password, user.passwordHash()))
                                        .<Result<UserPrincipal>>map(user -> Result.success(user))
                                        .orElseGet(() -> new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                                "invalid credentials")));"""),
                        text("bp:sec-close", """
                                Second: INPUT IS HOSTILE, SO THE EDGE VALIDATES AND THE LOGS FORGET. Every demo controller bounds its parameters before use (the requireInRange pattern you met in topic after topic); every wire-facing factory judges rather than trusts (PostDraft.of again — the polite front desk); every adapter re-parses identifiers it could have assumed (PgPostRepository turns a hostile id into NOT FOUND, never a driver 500). The logging side of the same habit: the validation handler logs field NAMES only — messages and rejected values stay off the line, because one of those fields may be a password; the PII topic's redaction rules run on the same principle. Third, named once: LEAST PRIVILEGE IN WIRING — deny-by-default method security, cookies with http-only + same-site, secrets from the environment and marked-as-secret in config so an inspector demonstrates REDACTION instead of echo. None of this is a feature you add in a security sprint; it is the shape every file is written in from day one, which is precisely why it survives contact with deadlines.""")),
                section("data", "Data: values bind, ports own the truth",
                        "Two habits keep a data layer honest for years: every value travels as a bind parameter, and the domain — not the driver — defines what the words mean.",
                        text("bp:data-intro", """
                                THE FIRST DATA HABIT IS THE OLDEST ONE IN SOFTWARE: PARAMETERIZE EVERYTHING. The jdbc topic's rule, restated as the adapter's actual structure: the only string-composed SQL is STRUCTURE — which fixed filter clause, which whitelisted ORDER BY column from a typed enum — and every VALUE binds. There is no third kind of SQL text in the data layer. Look at the posts adapter's update statement and notice what is absent: there is no concatenation branch, no "trusted input" exception, no place an injection could live even by accident:
                                """),
                        code("bp:pg-update", "java",
                                "guide/data/posts/PgPostRepository.java — every value a bind parameter, the version check IN the WHERE clause: optimistic concurrency as one atomic step",
                                "server/src/main/java/guide/data/posts/PgPostRepository.java", """
                                private static final String UPDATE_SQL =
                                        "UPDATE posts SET title = ?, body = ?, status = ?, tags = ?, updated_at = ?,"
                                                + " version = version + 1"
                                                + " WHERE id = ? AND version = ?"
                                                + " RETURNING " + SELECT_COLUMNS;"""),
                        text("bp:data-port", """
                                THE SECOND HABIT: THE DOMAIN OWNS THE CONTRACT, AND THE CONTRACT CANNOT LIE. Persistence details live behind ports — interfaces in domain/, adapters in data/, wiring in config/ — and the interesting best practice is not the layering itself but the SHAPE of the port's types. The posts port's update contract is deliberately TRI-STATE: "missing" and "stale version" are different facts that become different statuses (404 vs 409), and a port that returned Optional<Post> would force every caller to guess which failure happened. Making invalid states unrepresentable — sealed interfaces, records, enums over strings — is the cheapest security and correctness tool in the box, and it composes:
                                """),
                        code("bp:post-port", "java",
                                "guide/domain/posts/PostRepository.java — the tri-state update contract: the port cannot lie about WHY an update did not happen",
                                "server/src/main/java/guide/domain/posts/PostRepository.java", """
                                sealed interface Update permits Updated, NotFound, StaleVersion {
                                }

                                /** The stored post replaced; carries the new version. */
                                record Updated(Post post) implements Update {
                                }

                                /** No post carries this id — the caller maps this to a 404-shaped failure. */
                                record NotFound() implements Update {
                                }

                                /** The stored version moved on; carries the version that IS current — the caller maps this to a 409-shaped failure. */
                                record StaleVersion(long currentVersion) implements Update {
                                }"""),
                        text("bp:data-close", """
                                Around the pair, the supporting habits the data topics built: MIGRATIONS ARE HISTORY (Flyway's ledger is the only DDL that ever runs — no hand-edited schemas, no "just run this once"), IDENTITIES ARE MINTED AT BIRTH by the adapter so a row's key is never null-or-maybe, and TIMESTAMP ARITHMETIC IS DONE CONSCIOUSLY (micro-truncated before insert so what the app returns is exactly what PG stored). None of these are glamorous; all of them are the difference between a data layer you trust at 4 a.m. and one you re-verify with a SELECT before every decision.""")),
                section("reliability", "Reliability: failure is an input",
                        "A service that calls other services will experience their failures. The practice is to treat failure as a designed input — typed, bounded, and behind a port the domain owns.",
                        text("bp:rel-intro", """
                                THE RELIABILITY POSTURE OF THIS APP IS ONE SENTENCE: EVERY DEPENDENCY GETS A TIMEOUT, A RETRY POLICY WITH JITTER, AND — WHERE THE DEPENDENCY IS FLAKY ENOUGH TO MATTER — A CIRCUIT BREAKER. The reliability topic built the machinery; the best practice is where it LIVES: behind a domain port, so the domain speaks in state names and typed rejections while the library stays a wiring detail. That placement is not aesthetic. A breaker wired directly into a use case makes the library UNTESTABLE-WITHOUT and UNREPLACEABLE-WITHOUT a rewrite; behind BreakerPort, the domain's tests run against a stub in one line and the production adapter is a one-bean swap. This is the dependency rule applied to a LIBRARY rather than a layer — same judgment, different target:
                                """),
                        code("bp:breaker-port", "java",
                                "guide/domain/reliability/BreakerPort.java — resilience machinery behind a port the domain owns: state names, counters, a typed rejection — never a library exception",
                                "server/src/main/java/guide/domain/reliability/BreakerPort.java", """
                                public interface BreakerPort {

                                    <T> T execute(Supplier<T> call);

                                    String state();

                                    Snapshot snapshot();

                                    void forceHalfOpen();

                                    void reset();
                                }"""),
                        text("bp:rel-close", """
                                The rest of the posture, each piece taught by its topic and each one a default rather than a debate: DEGRADATION BEATS DEATH (the breaker's OPEN state answers in microseconds with a typed 503 instead of piling a second timeout onto an already-late caller), CONCURRENCY IS BUDGETED (pool sizes are arithmetic against a real number, not a vibe — HikariCP's 8 is a budget this app can defend), and FAILURE MODES ARE TESTED, not imagined — the demo theater's dial forces half-open probes, rejected calls, and recovery on demand, deterministically, because a breaker whose OPEN path has never run in a test is a breaker that will surprise you in production. The habit that wraps them all: WHEN YOU CALL SOMETHING, DECIDE WHAT HAPPENS WHEN IT DOESN'T ANSWER. The undecided case is the outage.""")),
                section("observability", "Observability: fields, not strings",
                        "You cannot debug what you cannot query. One JSON line per request, ids that join every record, and levels that mean something — the cheapest always-on observability there is.",
                        text("bp:obs-intro", """
                                THE OBSERVABILITY FLOOR IS ONE LINE PER REQUEST, WRITTEN ON COMPLETION, AS FIELDS. The filter below is the whole story: method, path, status, duration — plus the request id and trace id riding in MDC, which the JSON encoder carries as FIELDS rather than string-splicing into the message. Fields are the practice: a log line whose parts are queryable can be counted, filtered, and joined; a log line that says "request failed for user 42!!" is archaeology. Note the deliberate omissions, because they are the load-bearing kind: no request/response bodies (memory under load, secrets in the path, and the error envelope already carries the story), and /actuator/** is excluded — health probes fire every few seconds in every environment, and logging them is how one-line-per-request becomes noise that hides signal:
                                """),
                        code("bp:log-filter", "java",
                                "guide/presentation/filter/RequestLoggingFilter.java — one line per request, on completion: duration measured, path gated to /api/, ids riding in MDC as fields",
                                "server/src/main/java/guide/presentation/filter/RequestLoggingFilter.java", """
                                long startNs = System.nanoTime();
                                try {
                                    filterChain.doFilter(request, response);
                                } finally {
                                    long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                                    String method = request.getMethod();
                                    String path = request.getRequestURI();
                                    int status = response.getStatus();
                                    if (path.startsWith("/api/")) {
                                        logCompletion(method, path, status, durationMs, response);"""),
                        text("bp:obs-levels", """
                                And the level contract — tiny, and worth copying verbatim into every service you write: 2xx/3xx at INFO (a normal beat), 4xx at WARN (a client we could not satisfy — noise worth noticing), 5xx at ERROR (we broke, page someone). Levels are a queueing system for human attention; a codebase that logs 4xx at ERROR trains its on-call to ignore ERROR. The mapping is three if-statements, which is exactly the right size for a contract:
                                """),
                        code("bp:log-levels", "java",
                                "guide/presentation/filter/RequestLoggingFilter.java — the level-by-status contract: 2xx/3xx info, 4xx warn, 5xx error",
                                "server/src/main/java/guide/presentation/filter/RequestLoggingFilter.java", """
                                static Level levelFor(int status) {
                                    if (status >= 500) {
                                        return Level.ERROR;
                                    }
                                    if (status >= 400) {
                                        return Level.WARN;
                                    }
                                    return Level.INFO;
                                }"""),
                        text("bp:obs-close", """
                                Around the line-per-request floor, the metrics and tracing habits the observability topic added: METERS NOT LOG-LINES for anything you will graph (the same stopwatch that writes the log line feeds a timer — one measurement, two outputs), COUNTERS WITH FLAT CARDINALITY (the outcome tag has three possible values, ever — an unbounded tag like userId is a metrics-store outage), and TRACE IDS THAT JOIN (W3C ids flow across hops so "which request was slow?" has an answer that is one query, not one afternoon). The closing habit is the request id itself: stamped by a filter, echoed in the response, rejoined into the completion log line — the smallest piece of observability in the app and the one that pays for itself the first time a user pastes an id into a support ticket.""")),
                section("performance", "Performance: measure, then decide",
                        "Every performance claim in this guide was measured by code in this repo. The practice is not 'be fast' — it is the discipline that separates numbers you know from numbers you hope.",
                        text("bp:perf-intro", """
                                THE PERFORMANCE PRACTICE IS A SHAPE OF WORK, NOT A NUMBER: warm up before timing (pass one measures the JIT, not the code), report MEDIANS over many passes (never one lucky sample), assert what is deterministic (bytes in, bytes out — the ratio) and never what is not (micros move run to run), and run on REAL-SHAPED input (gzip's ratio on degenerate input teaches the wrong lesson). The compression benchmark behind the perf:bench demo rehearses all four in about fifteen lines — and the reading lines it emits say what the numbers mean instead of rounding claims it did not time:
                                """),
                        code("bp:bench", "java",
                                "guide/domain/performance/CompressionBenchmark.java — the measurement discipline in miniature: warm-up untimed, medians over passes, deterministic ratios",
                                "server/src/main/java/guide/domain/performance/CompressionBenchmark.java", """
                                byte[] compressed = gzip(raw, level);
                                for (int warmup = 0; warmup < WARMUP_PASSES; warmup++) {
                                    gunzip(gzip(raw, level));
                                }
                                long[] compressNanos = new long[passes];
                                long[] roundTripNanos = new long[passes];
                                for (int pass = 0; pass < passes; pass++) {
                                    long start = System.nanoTime();
                                    byte[] out = gzip(raw, level);
                                    long mid = System.nanoTime();
                                    gunzip(out);
                                    long end = System.nanoTime();
                                    compressNanos[pass] = mid - start;
                                    roundTripNanos[pass] = end - start;
                                }"""),
                        text("bp:perf-vt", """
                                And the one performance decision this repo made globally, because it is the best current default for the blocking-I/O shape almost every business backend has: VIRTUAL THREADS, one yaml line, twelve-factor overridable. The performance topic measured the trade; the reactive topic measured it again against a Reactor pipeline and the honest verdict stood — for blocking I/O at realistic scale, one virtual thread per request matches or beats the pipeline with code that reads like the domain. The decision lives in configuration, not code, which is itself the practice — architecture-level choices belong where you can SEE them and change them per environment:
                                """),
                        code("bp:vt-yml", "yaml",
                                "server/src/main/resources/application.yml — the app's global concurrency decision as one overridable line",
                                "server/src/main/resources/application.yml", """
                                # VIRTUAL. One line flips Boot's MVC stack onto one virtual thread per
                                # request: a handler parked on a database call, a redis round trip, or an
                                # upstream HTTP hop holds no OS thread while it waits.
                                threads:
                                  virtual:
                                    enabled: true"""),
                        text("bp:perf-close", """
                                The remaining habits are the performance topic's checklist, compressed: BUDGET YOUR POOLS (connections, servlet threads if platform, executors — every pool is a number someone should be able to defend), COMPRESS AT THE EDGE with a floor (responses under a kilobyte cost more CPU to compress than they save bytes — the benchmark measured where the floor is), and PROFILE BEFORE YOU OPTIMIZE (JFR and async-profiler answer 'where is the time?' in minutes; intuition answers it correctly slightly less often than a coin). The anti-habit to retire: micro-optimizing code the profiler has never named. The profiler is always right about where the time is, and it has never once named a string concatenation in this codebase.""")),
                section("operations", "Operations: the config you ship is the app",
                        "The running service is the product; the jar is just how it gets there. Probes, graceful shutdown, and budgets are written down as config — reviewable, diffable, honest.",
                        text("bp:ops-intro", """
                                THE OPERATIONS PRACTICES ARE ALL THE SAME PRACTICE: WRITE THE OPERATIONAL CONTRACT DOWN. The k8s manifest is the canonical example — the deployment does not merely run the image, it states what HEALTH MEANS (two probes, two meanings: liveness is "restart me" for a dead process, readiness is "stop routing to me" while booting or degraded — mixing them up is the classic cascade-restart outage), what REPLICA SAFETY MEANS (maxUnavailable: 0 with a readiness gate is zero-downtime deploys in four lines of yaml), and what RESOURCES MEAN (requests are the scheduler's promise, limits are the OOM ceiling):
                                """),
                        code("bp:k8s-probes", "yaml",
                                "deploy/k8s/deployment.yml — the probe pair on the actuator's split health endpoints: liveness restarts, readiness routes",
                                "deploy/k8s/deployment.yml", """
                                livenessProbe:
                                  httpGet:
                                    path: /actuator/health/liveness
                                    port: http
                                  periodSeconds: 10
                                  failureThreshold: 3
                                readinessProbe:
                                  httpGet:
                                    path: /actuator/health/readiness
                                    port: http
                                  periodSeconds: 5
                                  failureThreshold: 2"""),
                        text("bp:ops-graceful", """
                                The same write-it-down discipline applies to SHUTDOWN, which is an operation whether you schedule it or Kubernetes does. Boot's default is an IMMEDIATE stop: in-flight requests die mid-response. The deliberate posture, one line plus a budget: on SIGTERM, stop accepting new connections, let in-flight work finish, and force onward after thirty seconds — so a hung request cannot pin the process open forever, and the pod's terminationGracePeriodSeconds (45s, deliberately LONGER) covers the whole dance before the kubelet SIGKILLs. The k8s preStop sleep, the grace period, and this line are one choreography split across two files:
                                """),
                        code("bp:graceful-yml", "yaml",
                                "server/src/main/resources/application.yml — graceful shutdown as written-down posture, bounded by a phase budget",
                                "server/src/main/resources/application.yml", """
                                server:
                                  port: 8080
                                  # …
                                  shutdown: graceful"""),
                        text("bp:ops-close", """
                                The closing operational habits, each one a file in deploy/ or .docker/: THE IMAGE IS THE SAME ARTIFACT EVERYWHERE (multi-stage build, layered jars for cache-friendly rebuilds, one build promoted through environments by configuration), CONFIG IS INJECTED NOT BAKED (the configmap carries the GUIDE_* rails; secrets never live in the image), and PARITY IS CHECKED, not hoped (the deploy demo reads the real compose file through a typed mirror and reports env-parity rails — the gap between compose and k8s is rendered, not remembered). Operations done this way has a property nothing else in this topic has: when something breaks at 4 a.m., the contract for what SHOULD be happening is sitting in reviewable text files, and the diff between intended and actual is one command.""")),
                section("git-review", "Git and review: the suite is the definition of done",
                        "The workflow practices are what keep every other practice honest. Small diffs, CI on every push, and a review culture that reads tests before prose.",
                        text("bp:git-intro", """
                                THE GIT PRACTICE THIS REPO MODELS: THE SUITE IS THE DEFINITION OF DONE, AND CI RUNS IT ON EVERY PUSH. The workflow file is short enough to read in one sitting and that brevity is the point — per package, one job, the same commands a developer runs locally (mvn -B verify — verify, not test, so the JaCoCo coverage gate bound to that phase is enforced in CI too; npm ci, test, build), so a green check on a pull request MEANS the same thing a green terminal means. Every guard this topic has named is in that suite: the dependency-rule test scans imports, the snippet-source test verifies teaching code against real files, drift tests pin the OpenAPI paths. A best practice that lives only in a CONVENTIONS.md is a suggestion; the same practice wired into CI is a floor:
                                """),
                        code("bp:ci", "yaml",
                                ".github/workflows/ci.yml — the definition of done, on every push: per package, the same commands a developer runs locally",
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
                                      - run: mvn -B verify"""),
                        text("bp:git-close", """
                                Around CI, the git habits that make review possible: SMALL DIFFS (a pull request a reviewer can hold in their head gets a real review; a 3000-line Friday dump gets a glance and an approve), COMMITS THAT EXPLAIN WHY (the diff already says what), and TESTS REVIEWED AS CODE (the reviewer's first question for a new endpoint is 'where is its test?', because in this repo every demo endpoint arrives with one — the invariant that keeps 1100+ tests meaningful rather than aspirational). The branch discipline is deliberately boring: trunk-based with short-lived branches, because the expensive part of git workflow is not merging, it is DRIFT — the longer a branch lives, the more it diverges from the truth CI is defending. Ship small, ship often, let the suite say no.""")),
                section("human-practices", "Human practices: write for the next engineer",
                        "The hardest practices are social. Names, honesty about uncertainty, and code a tired teammate can read — the habits that decide whether your codebase is a place people stay.",
                        text("bp:human-intro", """
                                EVERY PRACTICE ABOVE IS MECHANICAL COMPARED TO THESE. First: MAKE INVALID STATES UNREPRESENTABLE, AND THE VALID ONES OBVIOUS. The Result type is the repo-wide instance — expected failures are VALUES, in a sealed type, so a caller literally cannot forget the failure case exists without the compiler noticing. The habit generalizes: records over maps, enums over strings, sealed interfaces over instanceof chains, tri-state ports over Optional-with-guesswork. Every one of those choices moves a runtime question into compile time, and compile time is the cheapest time to be wrong:
                                """),
                        code("bp:result", "java",
                                "guide/core/result/Result.java — failures as values in a sealed type: the compiler carries the checklist",
                                "server/src/main/java/guide/core/result/Result.java", """
                                public sealed interface Result<T> permits Success, Failed {

                                    static <T> Result<T> success(T value) {
                                        return new Success<>(value, java.util.List.of());
                                    }
                                    // …
                                    static <T> Result<T> failed(guide.core.errors.Failure failure) {
                                        return new Failed<>(failure);
                                    }"""),
                        text("bp:human-middle", """
                                Second: NAMES ARE THE DOCUMENTATION THAT CANNOT ROT. requireInRange, PostDraft.of, BreakerPort.forceHalfOpen, RequestLoggingFilter — a comment describing what code does decays the day the code changes; a name that says what the code does is re-read every time the file is open. This repo's javadoc habit follows the same rule: the comment explains WHY — the judgment, the trade-off, the load-bearing surprise (every 'FLAGGED APPEND' in application.yml is a decision with its reasoning inline) — never a narration of the next four lines.

                                Third: HONESTY ABOUT UNCERTAINTY, in code and in prose. Benchmarks report medians AND say the micros move; the scaling demo reports what is LOST when state goes local instead of pretending statelessness is free; the reactive topic reports the regime where its own posture loses. A codebase that admits its numbers' error bars is one you can trust when it doesn't hedge — and teaching material that models that honesty (this guide's demos assert thresholds, never exact timings) is training the habit in the next generation of the team.

                                Fourth: WRITE FOR THE TIRED READER. The person who reads your diff at 4 a.m. during an incident is you, exhausted. Small public classes with one job, constructors over hidden wiring, constants with names, and the discipline of stating the obvious ('deliberately NO further request: the consumer is slow') — the comments that explain a DELIBERATE OMISSION are worth ten that explain an inclusion, because the omission is what the reader will not believe without help.""")),
                section("anti-checklist", "The anti-checklist (and the live one)",
                        "A checklist you never run is decoration. This demo is the checklist RUNNING: the app grades itself where it can see, and hands you a curl where it cannot.",
                        text("bp:check-intro", """
                                THE PROBLEM WITH BEST-PRACTICE LISTS is that reading one verifies nothing. So this topic's demo is the opposite of a poster: GET /api/demo/best/checklist serves every practice from this topic as a row — category, id, title — and for each row either a LIVE VERDICT the app self-reported from in-process state, or a VERIFY pointer whose curl output IS the verdict. The split is the honesty: things the app can see about itself (the taxonomy registered in the FailureKind enum, the content registry that validated at startup, the effective virtual-threads and shutdown properties) get real verdicts, deterministic by construction; things that are ENVIRONMENTAL (CI green, a database actually parameterizing, a probe answering) get a pointer, because a checklist that claims a pass it cannot see is exactly the decoration this section is against. The two item builders are the whole mechanism:
                                """),
                        code("bp:check-engine", "java",
                                "guide/domain/best/PracticeChecklist.java — the two verdict kinds: a live self-report, or a verify pointer — never a claimed pass the app cannot see",
                                "server/src/main/java/guide/domain/best/PracticeChecklist.java", """
                                private static CheckItem live(String category, String id, String title,
                                        boolean verdict, String evidence) {
                                    return new CheckItem(category, id, title, verdict ? VERDICT_PASS : "fail", evidence, null);
                                }

                                private static CheckItem verify(String category, String id, String title,
                                        String whatToLookFor, String verifyCommand) {
                                    return new CheckItem(category, id, title, VERDICT_VERIFY, whatToLookFor, verifyCommand);
                                }"""),
                        demo("bp:demo", "best:checklist", "The guide grades itself",
                                "The practices from this topic as a live checklist: the app self-reports the verdicts it can see in-process — one error taxonomy, registry validated at startup, virtual threads on, graceful shutdown, probes and metrics exposed — and hands you a curl for the environmental ones. Counts, categories, and zero claimed passes it cannot see."),
                        text("bp:check-client", """
                                The client panel is one GET and renders exactly what the server graded — grouped by category, verdict badges for live rows, the curl command in monospace for verify rows. No client-side math, the server's verdicts are the lesson:
                                """),
                        code("bp:check-client-fetch", "ts",
                                "client/src/ui/demos/best/checklist_demo.tsx — one GET, the server's verdicts rendered as-is",
                                "client/src/ui/demos/best/checklist_demo.tsx", """
                                void safeRepositoryCall(async () =>
                                  parseChecklist(await requestJson(config, '/demo/best/checklist')),
                                ).then((result) => {""")),
                section("terminal-companion", "Terminal companion",
                        "The capstone tour: the checklist live, then each verify pointer run for real — every practice named in this topic, one curl at a time.",
                        text("bp:tc-intro", """
                                Run the checklist first, then cash the verify pointers. Every command below is one of the demo's own rows, executed:
                                """),
                        code("bp:tc-curl", "bash",
                                "the best-practices tour, end to end",
                                null, """
                                # the checklist itself: counts first, then rows grouped by category —
                                # live verdicts read from THIS process, verify rows hand you a curl
                                curl -s localhost:8080/api/demo/best/checklist \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); \\
                                  print(f"{r[\\"counts\\"][\\"livePasses\\"]}/{r[\\"counts\\"][\\"liveChecks\\"]} live pass, \\
                                {r[\\"counts\\"][\\"verifyPointers\\"]} to verify"); \\
                                  [print(f"[{i[\\"category\\"]:>13}] {i[\\"verdict\\"]:>6}  {i[\\"title\\"]}") for i in r["items"]]'
                                #   6/6 live pass, 10 to verify
                                #         [        setup]   pass  Configuration is one typed, boot-validated record
                                #         [        setup]   pass  Content ids are unique, validated at startup
                                #         [          api]   pass  One error taxonomy, one envelope end to end
                                #           [ security] verify  Passwords are hashed, never stored or logged
                                #           [      data] verify  Values bind; only structure is composed SQL
                                #            [     … ]   …

                                # one live verdict, inspected: the taxonomy as the app registered it
                                curl -s localhost:8080/api/demo/best/checklist \\
                                  | python3 -c 'import json,sys; \\
                                  item=[i for i in json.load(sys.stdin)["items"] if i["id"]=="one-error-envelope"][0]; \\
                                  print(item["evidence"])'
                                # 8 FailureKinds carry one HTTP status and one wire code each (400
                                # validation_failed … 500 internal_error); every failure renders …

                                # the error envelope in the wild — an unrouted path, still the contract
                                curl -si localhost:8080/api/content/nope | head -4
                                # HTTP/1.1 404
                                # {"error":{"code":"not_found","message":"No static resource …","requestId":"…"}}

                                # a verify row cashed: parameterized SQL, both doors
                                curl -s -X POST localhost:8080/api/demo/db/sql/safety \\
                                  -H 'content-type: application/json' -d '{}'

                                # breaker state through the port's own vocabulary
                                curl -s localhost:8080/api/demo/reliability/breaker | head -12

                                # measured-not-guessed: medians, two levels, the same engine the panel runs
                                curl -s 'localhost:8080/api/demo/perf/bench?payload=medium&passes=16' | head -8

                                # the operational pair: readiness answering, metrics exposed
                                curl -s localhost:8080/actuator/health/readiness
                                curl -s localhost:8080/actuator/prometheus | grep guide_http_server_requests | head -3

                                # the process row: CI's latest verdict, straight from the source
                                curl -s 'https://api.github.com/repos/Mayank-UK/spring_boot_developers_guide/actions/runs?per_page=1' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin)["workflow_runs"][0]; \\
                                  print(r["name"], r["status"], r["conclusion"])'
                                # CI completed success"""),
                        text("bp:tc-close", """
                                That tour is the topic's thesis in executable form: nothing on the checklist is taken on faith — the app's own state answered the live rows, and one curl each answered the rest. Forty-one topics taught you the machinery; this one asks you to keep it honest. The two capstones that follow take the posture outward — third-party integrations (the same disciplines when the dependency is someone else's service) and advanced architecture (the same vocabulary when one system becomes several). Bring this checklist with you; it travels.""")));
    }
}
