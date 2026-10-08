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
 * Topic 33 — Observability. Group platform, order 4. The three questions
 * (is it up, is it right, is it fast) and the three answers built into this
 * app for real: structured JSON logs with correlation ids, an always-on
 * request-completion line, Micrometer meters on genuine events, the
 * actuator's liveness/readiness split told honestly against this app's own
 * degrade-gracefully decisions, and Micrometer Tracing with a W3C
 * traceparent the demo carries across a real HTTP self-call — one id,
 * proven across every hop.
 */
public final class ObservabilityTopic {

    private ObservabilityTopic() {
    }

    public static Topic topic() {
        return new Topic("observability", "Logging and Observability",
                "The three questions you will be asked at 3 a.m. — is it up, "
                        + "is it right, is it fast — and the three machineries this app runs "
                        + "to answer them: structured logs an id can join, meters on real "
                        + "events, and a trace id that survives an HTTP hop. Plus the honest "
                        + "parts nobody demos: sampling costs, alert fatigue, and why "
                        + "restarting is not a strategy.",
                TopicGroupId.PLATFORM, 4,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-three-questions", "The three questions",
                        "Is it up? Is it right? Is it fast? Every production incident is one of these three — and each has a machinery built to answer it.",
                        text("tq:map", """
                                When something is wrong in production you are answering one of three questions. IS IT UP — is the process alive, accepting work? That answer belongs to the platform: health probes the orchestrator polls, and a process that exits when it should. IS IT RIGHT — is it doing correct work? That answer belongs to logs and traces: the evidence trail of what actually happened to a specific request. IS IT FAST — is it doing that work at acceptable latency and throughput? That answer belongs to metrics: numbers over time, comparable across versions and load.

                                Logs, metrics, traces are not three spellings of "monitoring" — each is shaped for its question. A LOG is an event: something that happened, at a time, with context. It is human-shaped when read one at a time and worthless at scale unless STRUCTURED — parseable, greppable, joinable on ids. A METRIC is a number over time: cheap to collect and graph, but it has amnesia — a counter tells you how many, never which ones. A TRACE is a story: one request's path through the system as a set of named, timed spans, tied together by one id — it answers "where did THIS request's time go", the question neither log lines nor averages can.

                                The honest framing is observability VERSUS debugging-after-the-fact. Debugging is what you do after the surprise: add a log line, redeploy, wait for a recurrence. Observability is making the system legible BEFORE the surprise — the ids, fields, meters, and spans are installed while you are calm, so during the incident you are READING, not digging. The difference is not tooling; it is when the work happens. Every piece of that pre-work is running in this app right now — the page you are reading is being logged, timed, and traced by the machinery it teaches."""),
                        text("tq:costs", """
                                Two honesty notes before the machinery. First, observability is not free: every log line costs disk and CPU, every span costs allocations — the sections below pick costs deliberately (what is always-on, what is sampled, what is simply not captured). Second, no instrumentation answers a question you did not think to instrument: the metrics section registers counters for events THIS app cares about. Deciding which questions you will want to have asked is the real work; the framework, the registry, and the tracer are just the pens.""")),
                section("structured-logging", "Structured logging: logs a machine can join",
                        "One JSON object per event, MDC keys as fields — the same log line a human skims in dev and a platform indexes in prod, joined across services by requestId and traceId.",
                        text("sl:why-json", """
                                A classic log line is prose for a human: `2026-10-06 10:14:22 WARN Login failed for user bob`. Readable, and nearly useless at scale — "find every login failure for bob's requestId and show me the trace" becomes a regex archaeology dig through gigabytes. STRUCTURED logging flips the format: each event is one JSON object with named fields, so the platform that ingests it can index, filter, and JOIN. The same information, arranged as data:

                                `{"@timestamp":"2026-10-06T10:14:22.114Z","level":"WARN","logger":"guide.http","message":"POST /api/demo/auth/login 401 8ms","requestId":"7f3a…","traceId":"4bf9…","app":"springboot-developers-guide"}`

                                The two fields doing the heavy lifting are correlation ids: requestId (which request) and traceId (which end-to-end operation). The request id is stamped by the filter you met in the filters topic, landing in MDC — the per-thread context Logback reads automatically:"""),
                        code("sl:requestid", "java",
                                "guide/core/logging/RequestIdFilter.java — the request id lands in MDC, where every appender finds it",
                                "server/src/main/java/guide/core/logging/RequestIdFilter.java", """
                                public class RequestIdFilter extends OncePerRequestFilter {

                                    public static final String MDC_KEY = "requestId";

                                    @Override
                                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                                            throws ServletException, IOException {
                                        // … the id is honored from a safe incoming header or generated
                                        MDC.put(MDC_KEY, requestId);
                                        try {
                                            filterChain.doFilter(request, response);
                                        } finally {
                                            MDC.remove(MDC_KEY);
                                        }
                                    }
                                }"""),
                        text("sl:split-intro", """
                                The JSON personality comes from ONE new dependency and ONE config file. The dependency is logstash-logback-encoder (9.0, chosen on Central — not managed by the Boot BOM; verified compatible with Boot 4.1.1's Logback 1.5.x line). The file is logback-spring.xml — and owning it REPLACES Boot's logging defaults: the console pattern, the JSON encoder, and the profile split all live here now. The split is the interesting part: dev gets the readable console a human tails; every other profile (default, prod, tests) gets JSON — because a log platform is the audience that matters wherever the app runs for real:"""),
                        code("sl:encoder-dep", "xml",
                                "server/pom.xml — the encoder dependency (version explicit: the Boot BOM does not manage it)",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>net.logstash.logback</groupId>
                                    <artifactId>logstash-logback-encoder</artifactId>
                                    <version>9.0</version>
                                </dependency>
                                <!-- … the tracing pair and the prometheus registry join below -->"""),
                        code("sl:logback", "xml",
                                "server/src/main/resources/logback-spring.xml — JSON by default, readable console under the dev profile, the teaching ring in both",
                                "server/src/main/resources/logback-spring.xml", """
                                <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                                    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                                        <customFields>{"app":"${appName}"}</customFields>
                                        <timeZone>UTC</timeZone>
                                    </encoder>
                                </appender>

                                <!-- … the readable console appender and the bounded OBS_RING appender -->

                                <springProfile name="dev">
                                    <root level="INFO">
                                        <appender-ref ref="PATTERN_CONSOLE"/>
                                        <appender-ref ref="OBS_RING"/>
                                    </root>
                                </springProfile>

                                <springProfile name="!dev">
                                    <root level="INFO">
                                        <appender-ref ref="JSON_CONSOLE"/>
                                        <appender-ref ref="OBS_RING"/>
                                    </root>
                                </springProfile>"""),
                        text("sl:field-discipline", """
                                Field discipline — what goes IN a structured event, enforced by habit rather than a schema: timestamps and levels always; ids that join (requestId, traceId) always; and NEVER payloads or secrets. Not the request body (it can be megabytes and it is not yours to log), not the Authorization header, not the password-shaped fields — the security topic's PII rules apply with extra force here, because logs are copied, shipped, retained, and read by people who never saw the original request. A log line is a disclosure you cannot take back. When a failure needs the shape of the input, log its FIELDS (postId, kind, field) the way the error envelope's details do — structured, minimal, safe.

                                The reader-side payoff is the join: every event carries the ids, so `requestId=X` finds the request's completion line, its error envelope, and the client's report of the same failure — one story assembled from three angles. The demo below reads the app's actual ring buffer (the OBS_RING appender from that xml — a second destination for the same events, bounded and thread-safe) and renders what it holds:"""),
                        code("sl:ring", "java",
                                "guide/data/obs/LogRingAppender.java — the bounded, thread-safe teaching appender behind the viewer",
                                "server/src/main/java/guide/data/obs/LogRingAppender.java", """
                                public final class LogRingAppender extends AppenderBase<ILoggingEvent> implements LogRing {

                                    private final ArrayDeque<LoggedEvent> ring = new ArrayDeque<>();

                                    private int capacity = DEFAULT_CAPACITY;

                                    @Override
                                    protected void append(ILoggingEvent event) {
                                        synchronized (ring) {
                                            ring.addLast(new LoggedEvent(
                                                    event.getTimeStamp(),
                                                    // … level, logger, message
                                                    event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY),
                                                    event.getMDCPropertyMap().get(TRACE_MDC_KEY)));
                                            while (ring.size() > capacity) {
                                                ring.removeFirst();
                                            }
                                        }
                                    }
                                }"""),
                        demo("sl:viewer-demo", "obs:viewer", "The log ring, live",
                                "Read the app's recent structured events — every field the JSON encoder carries — and deliberately emit new ones at each level. Filter by level, watch ids appear and join."),
                        code("sl:viewer-ts", "ts",
                                "client/src/ui/demos/obs/viewer_demo.tsx — the ring as a live surface: fetch, filter, emit",
                                "client/src/ui/demos/obs/viewer_demo.tsx", """
                                const loadRing = useCallback(() => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () => {
                                    const response = await guardedGetJson(
                                      config,
                                      `/demo/obs/logs?level=${encodeURIComponent(level)}&limit=${String(limit)}`,
                                    );
                                    return parseRingBody(response.body);
                                  }).then((result) => {
                                    // … idle or error panel, exactly like every guarded fetch
                                  });
                                }, [config, level, limit]);

                                // … the effect that runs loadRing once on mount …
                                const emit = (nextLevel: string) =>
                                  void safeRepositoryCall(async () =>
                                    parseEvent((await guardedGetJson(config, `/demo/obs/logs/emit?level=${nextLevel}`)).body),
                                  ).then((result) => {
                                    if (result.ok) {
                                      loadRing();
                                    } else if (result.failure.code === 'validation_failed') {
                                      // the emitter's own door: an unknown level is a typed 400, kept visible
                                      setPanel({ kind: 'error', failure: result.failure });
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });"""),
                        text("sl:viewer-note", """
                                Note what the ring is NOT: it is not a second logging API. Events flow through the normal Logback pipeline, and the ring is simply another appender attached at the same root, seeing exactly what the JSON console sees. In-memory and bounded, it survives no restart and holds no history — a teaching window onto events a real deployment would ship to a platform. The FORMAT is the durable lesson, not the ring.""")),
                section("request-logging-filter", "One line per request",
                        "The cheapest always-on observability there is: a completion line per API request — method, path, status, duration — at a level that means something.",
                        text("rl:why", """
                                Between "log everything" and "log nothing" lives the single most useful log in any web app: ONE LINE PER REQUEST, written when the request COMPLETES. Not when it arrives — the completion line can carry the status and the duration, the two facts you actually want. It answers all three questions in miniature: up (it completed), right (the status), fast (the duration). This app registers exactly that as a servlet filter, always on:"""),
                        code("rl:filter", "java",
                                "guide/presentation/filter/RequestLoggingFilter.java — one completion line per /api request; the same stopwatch feeds the demo timer",
                                "server/src/main/java/guide/presentation/filter/RequestLoggingFilter.java", """
                                public final class RequestLoggingFilter extends OncePerRequestFilter {

                                    private static final Logger LOG = LoggerFactory.getLogger("guide.http");

                                    private final ObsMetrics metrics;

                                    @Override
                                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                                        long startNs = System.nanoTime();
                                        try {
                                            filterChain.doFilter(request, response);
                                        } finally {
                                            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                                            String method = request.getMethod();
                                            String path = request.getRequestURI();
                                            int status = response.getStatus();
                                            if (path.startsWith("/api/")) {
                                                logCompletion(method, path, status, durationMs, response);
                                                if (path.startsWith("/api/demo/")) {
                                                    metrics.recordDemoRequest(Duration.ofMillis(durationMs), outcomeFor(status));
                                                }
                                            }
                                        }
                                    }

                                    // … logCompletion rejoins the request id from the echoed header
                                    //   before writing the line (MDC was already cleared by then)
                                }"""),
                        text("rl:cost-accounting", """
                                The cost accounting, because always-on means the cost is always paid. The line carries four facts already in memory — no allocation beyond the formatted string, no I/O beyond the appender you already chose. What it deliberately does NOT do: capture request or response BODIES. Bodies cost memory proportional to traffic (a per-request buffer is how logging becomes the outage), they drag the security topic's redaction rules into the hot path, and they are usually redundant — the error envelope already tells the failure's story with safe fields. Metadata is cheap and safe; bodies are neither.

                                Two more deliberate choices: it skips /actuator/** (health probes arrive every few seconds, and logging them is how one-line-per-request becomes noise that hides the signal), and it is ordered AFTER the request-id and observation filters — the MDC fields its line relies on are stamped by filters that ran earlier; ordering is part of the chain's contract, not an afterthought.""")),
                section("levels-that-mean-it", "Levels that mean it",
                        "error is something broken NOW, warn is recovered-but-worry, info is a business beat, debug is flow. A level is a claim about urgency — the anti-patterns are all lies.",
                        text("lv:contract", """
                                A log level is not decoration — it is a CONTRACT with whoever reads the stream. ERROR: something is broken RIGHT NOW and a human should eventually look; the process or a feature is failing and nobody downstream will fix it. WARN: recovered, but worrying — a retry that saved you, a fallback taken, input refused; the system coped, and a pile of these is a leading indicator. INFO: business beats — posts created, jobs settled, requests completed; the story you would replay to know what the app did all day. DEBUG: flow — intermediate steps, deliberately off in production (TRACE, one notch further, is for chatty frameworks; this app keeps it available to one named logger only).

                                Every level abuse is a small lie that taxes someone later. The classics, as a table of shame:

                                — EVERYTHING-INFO: if it is all INFO then INFO means nothing; the person triaging greps blind and the on-call page gets ignored.
                                — LOG-AND-RETHROW DUPLICATION: catch, log ERROR, rethrow, and the global advice logs it again — one failure, two ERRORs, the incident channel thinks it is twice as bad. Rule: the layer that HANDS the failure to its renderer logs it; everyone else lets it fly.
                                — CATCH-TO-LOG-AND-SWALLOW: catch, log, return null — the log looks like honesty, but the caller now follows a null down a path of quiet wrongness. Prefer returning the failure as a value (this app's Result) so the log and the data flow agree.
                                — DEBUG-IN-PROD: leaving flow logs at INFO because "we might need them" — you will need the DISK more.

                                This app's emitter lets you fire each level for real and watch the ring record it — including the per-logger TRACE level overriding the root's INFO, which is exactly how a production app turns one noisy logger up without redeploying:"""),
                        code("lv:emitter", "java",
                                "guide/domain/obs/EmitDemoLog.java — five levels, one deliberate logger, read back from the ring",
                                "server/src/main/java/guide/domain/obs/EmitDemoLog.java", """
                                public final class EmitDemoLog {

                                    public static final String LOGGER_NAME = "guide.demo.obs.emitter";

                                    static {
                                        ch.qos.logback.classic.Logger logback =
                                                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LOGGER_NAME);
                                        logback.setLevel(ch.qos.logback.classic.Level.TRACE);
                                    }

                                    public Result<LoggedEvent> execute(String level) {
                                        // … unknown level → typed validation failure, like every boundary
                                        String message = "deliberate " + parsed + " emission from the observability tour";
                                        LOGGER.atLevel(parsed).log(message);
                                        metrics.emitted(normalized);
                                        return Result.success(newestMatching(message, parsed.toString()));
                                    }
                                }"""),
                        text("lv:note", """
                                The read-back is part of the lesson: the endpoint returns what the RING recorded, not what it asked for — if the pipeline had filtered the event (wrong level, no appender) you would see the gap, because the answer is built by finding the event in the ring rather than trusting the logger call to have worked. Logging is a pipeline like any other; observe its output, not its intentions.""")),
                section("actuator-liveness-vs-readiness", "Liveness vs readiness",
                        "Two probes, two contracts: restart me versus stop routing to me. The actuator ships both — and this app's health configuration is an honest case study in what belongs in each.",
                        text("al:contracts", """
                                Kubernetes (and every orchestrator that copied it) polls two different health endpoints with two different STAKES. LIVENESS ("should I RESTART this container?"): the process's right to exist — if liveness fails, the platform kills and replaces it. Readiness ("should I ROUTE traffic to this instance?"): the instance's fitness to serve right now — failing readiness pulls it from the load balancer while leaving it alive. The confusion of these two is a classic outage: a database blip marks the app not-live, the platform helpfully restarts EVERY instance at once, and the blip becomes an outage. Spring Boot's actuator maps them onto health GROUPS — /actuator/health/liveness and /actuator/health/readiness — enabled by one line this app already ships:"""),
                        code("al:probes", "yaml",
                                "server/src/main/resources/application.yml — probes on: the two groups mount; exposure widened later for prometheus",
                                "server/src/main/resources/application.yml", """
                                management:
                                  endpoints:
                                    web:
                                      exposure:
                                        include: health,info,prometheus
                                  endpoint:
                                    health:
                                      probes:
                                        enabled: true
                                  tracing:
                                    sampling:
                                      probability: 1.0"""),
                        text("al:db-story", """
                                Now the honest case study — this app's own health configuration, told straight. Phase 6 set `management.health.db.enabled: false`, and redis and mongodb got the same verdict. WHY: Boot mounts a DB health indicator that marks /actuator/health DOWN whenever PostgreSQL is unreachable — but this app is BUILT to serve with PG down (the datasource is lazy, the posts area falls back to the in-memory adapter; degrade-gracefully is the architecture, not an accident). With the indicator on, a PG blip would flip the whole health verdict, and anything routing on THAT would let an optional dependency decide the instance's fate — down for a reason that is not a reason.

                                The honest part is what that decision does NOT do: it does not claim PostgreSQL is healthy. It removes an OPTIONAL dependency from the liveness verdict, because here liveness means "can this process serve its API" — and it demonstrably can. What would a real deployment choose? Probably the reverse map: readiness that INCLUDES the datastore (do not send me traffic I cannot persist), liveness that includes almost nothing. Some go further and report each dependency as its own health group so the platform can see WHICH promise broke. All defensible; what is not, is inheriting Boot's defaults without asking which verdict each indicator deserves. Health is a routing decision — make it deliberately.

                                One more actuator affordance worth knowing: /actuator/info is yours to enrich — build info, git state, runtime versions — and it is the endpoint a human hits FIRST when asking "what IS running here?". Cheap to add, always honest, read-only forever.""")),
                section("metrics-with-micrometer", "Metrics with Micrometer",
                        "Counters, gauges, timers on real events — the numbers that answer is-it-fast and how-much, exposed in prometheus format by the actuator.",
                        text("mm:model", """
                                Micrometer (already on the classpath — the actuator brings it) is a facade over metric registries: you code against Meter and friends, and the REGISTRY — prometheus here — decides exposition. The meter shapes worth knowing: a COUNTER only goes up (`guide.posts.created` — how many posts were ever created; rates are computed at READ time from the slope, so counters stay cheap); a GAUGE is a current reading (`guide.obs.ring.size` — events in the ring right now; it can go down, and it is sampled, not event-driven); a TIMER records counts AND durations together (`guide.demo.requests` — how many demo requests and how long each took, with percentiles if you pay for them). Percentiles are the trap: MEAN latency lies (one slow request in a thousand hides in the average) — the numbers that matter are p95/p99, and the histogram machinery behind them costs memory per meter, so you publish them per timer deliberately, not globally.

                                Naming: Micrometer names use dots (`guide.jobs.outcomes`); the prometheus exposition translates to underscores (`guide_jobs_outcomes_total`) and appends _total for counters. TAGS (not name fragments!) carry dimensions — `outcome=succeeded` — and the cardinality rule is absolute: a tag per HTTP STATUS is fine (a few dozen values, bounded forever); a tag per REQUEST ID is an incident (unbounded series — your monitoring bill and your registry both die). This app's meters, all wired to real events:"""),
                        code("mm:meters", "java",
                                "guide/domain/obs/ObsMetrics.java — the app's meters: tag families pre-initialized, registration idempotent",
                                "server/src/main/java/guide/domain/obs/ObsMetrics.java", """
                                public final class ObsMetrics {

                                    public static final String[] JOB_OUTCOMES = {"succeeded", "failed", "dead"};

                                    public ObsMetrics(MeterRegistry registry) {
                                        this.registry = registry;
                                        for (String outcome : JOB_OUTCOMES) {
                                            jobOutcomeCounter(outcome);
                                        }
                                        // … the request-outcome and emit-level families pre-initialize the same way
                                    }

                                    public void postCreated() {
                                        registry.counter("guide.posts.created").increment();
                                    }

                                    public void jobOutcome(String outcomeWireName) {
                                        jobOutcomeCounter(outcomeWireName).increment();
                                    }

                                    public void recordDemoRequest(Duration duration, String outcome) {
                                        demoTimer(outcome).record(duration);
                                    }
                                }"""),
                        text("mm:events", """
                                Each meter is fed by a genuine event: the posts counter increments where a post is really created (the trace demo's hop — next section — creates one through the actual use case); the outcome counters reuse topic 29's vocabulary verbatim (succeeded/failed/dead) and increment from the real worker's settled records; the demo timer is fed by the request-logging filter's stopwatch — one measurement, a log line for humans and a meter for graphs. The tag families are PRE-INITIALIZED on purpose: a tagged counter does not exist until its first increment, so until the first job actually dies the exposition shows no `outcome="dead"` series at all — and an alert on "dead-letter jobs" would sit silently green on an absent series. Declaring the family up front makes zero an observable zero.

                                The summary endpoint serves a curated snapshot (this app's guide.* meters with their readings) PLUS the exact prometheus lines the exporter would ship — the same text the terminal tour curls. The demo never invents exposition; it scrapes the app's own registry. What the format looks like on the wire (abridged):

                                `# HELP guide_jobs_outcomes_total …` · `# TYPE guide_jobs_outcomes_total counter` · `guide_jobs_outcomes_total{outcome="succeeded"} 3.0`

                                Rate vs cumulative, the last distinction: everything above is CUMULATIVE since process start. Nothing here computes "requests per second" — the SCRAPER does, sampling the counter and differencing over its scrape interval. That division of labor is why counters stay dumb and cheap, and why restarting a process (which resets every counter) shows as a RATE SPIKE unless your dashboard compensates — a classic day-one graph artifact worth knowing before it misleads you. One naming surprise the exposition will hand you: `_created` is a RESERVED prometheus suffix (its own creation-timestamp series), so this app's `guide.posts.created` exposes as `guide_posts_total` — the sanitized name drops the verb. Grep for the exposition, not for your spelling of it.""")),
                section("tracing", "Tracing: one id across every hop",
                        "A W3C traceparent header, a set of named timed spans, and one id that survives an HTTP hop — proven live by a self-call you can watch.",
                        text("tr:anatomy", """
                                A trace is one id shared by every unit of work that contributes to one operation — the request's saga, not its average. The unit is the SPAN: a named, timed stretch (a handler, a client call, a job attempt), and spans link into a tree by parent id. The magic that makes it work across processes is a header you can read with your eyes — the W3C traceparent: `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`. Version 00; then the TRACE id (32 hex — the shared identity); then the SPAN id of the CURRENT context (16 hex — the parent the receiver will parent its own spans to); then flags (01 = sampled). That is the entire protocol: whoever receives a traceparent creates its spans UNDER that trace id, and when it calls the next hop it writes a new traceparent with the same trace id and its own span id. One line of header, and a request's story assembles itself across every service it touches.

                                The plumbing in this app is Micrometer Tracing bridged to Brave (the dependency pair in the pom — versionless through Boot's BOM). Spring's OBSERVATION API is the integration seam: the web stack opens a span per request, instrumented clients one per call, and the tracer turns observations into spans — plus log correlation: the current trace/span ids land in MDC, so every JSON log line between a span's start and end carries the trace id automatically. That MDC stamping is why the trace demo's logs JOIN to its spans with zero extra code.

                                The demo runs the whole story for real: it builds a traceparent BY HAND from the current context (the one-time visible version of what instrumented clients do silently), self-calls the app's hop endpoint over HTTP with it, and the hop — on another thread, in another Tomcat handler — joins the trace, does cross-layer work (creates a post, drains a job, logs a beat), and returns. The evidence: the hop's span and logs all carry the caller's trace id. One id, across every hop, proven:"""),
                        code("tr:probe", "java",
                                "guide/data/obs/RestClientTraceProbe.java — build the header by hand, self-call, then collect spans and logs of that trace id",
                                "server/src/main/java/guide/data/obs/RestClientTraceProbe.java", """
                                String traceId = current.context().traceId();
                                String traceparent = "00-" + traceId + "-" + current.context().spanId() + "-01";
                                int port = server.getWebServer().getPort();

                                HopWire hop;
                                try {
                                    hop = builder.clone().build()
                                            .get().uri("http://localhost:" + port + HOP_PATH + "?origin=traced-flow")
                                            .header("traceparent", traceparent)
                                            .header("x-request-id", HOP_REQUEST_ID)
                                            .retrieve().body(HopWire.class);
                                } catch (RestClientException transportFailure) {
                                    return Result.failed(Failure.of(FailureKind.INTERNAL,
                                            "the self-call failed: " + transportFailure.getMessage(), null));
                                }
                                // … the hop's trace id must EQUAL the caller's, or propagation broke"""),
                        code("tr:reporter", "java",
                                "guide/data/obs/RecordingSpanReporter.java — the teaching exporter: same extension point a Zipkin reporter would implement",
                                "server/src/main/java/guide/data/obs/RecordingSpanReporter.java", """
                                public final class RecordingSpanReporter implements SpanReporter, SpanTimeline {

                                    static final int CAPACITY = 256;

                                    private final ArrayDeque<TraceSpan> ring = new ArrayDeque<>();

                                    @Override
                                    public void report(FinishedSpan span) {
                                        synchronized (ring) {
                                            ring.addLast(new TraceSpan(
                                                    span.getName(),
                                                    span.getTraceId(),
                                                    span.getSpanId(),
                                                    span.getParentId(),
                                                    span.getStartTimestamp().toEpochMilli(),
                                                    span.getDuration().toNanos() / 1_000_000.0));
                                            while (ring.size() > CAPACITY) {
                                                ring.removeFirst();
                                            }
                                        }
                                    }
                                }"""),
                        demo("tr:trace-demo", "obs:trace", "One id across every hop",
                                "Run the traced flow: the app calls itself with a hand-built W3C traceparent, then shows the trace id, the span waterfall with timings, and the log lines carrying the SAME id. One id, every hop, as evidence."),
                        code("tr:trace-ts", "ts",
                                "client/src/ui/demos/obs/trace_demo.tsx — run the flow, render the waterfall and the matching log lines",
                                "client/src/ui/demos/obs/trace_demo.tsx", """
                                const runFlow = () => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () => {
                                    const response = await guardedGetJson(config, '/demo/obs/trace');
                                    return parseTraceEvidence(response.body);
                                  }).then((result) => {
                                    if (result.ok) {
                                      setEvidence(result.value);
                                      setPanel({ kind: 'idle' });
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                };"""),
                        text("tr:sampling", """
                                The span reporter above is TEACHING-GRADE, and says so: it implements the same SpanReporter interface a Zipkin exporter would, but keeps a bounded ring in memory instead of shipping spans anywhere. A real deployment reports to a collector that stores and indexes for days and can answer "show me the slowest traces of the hour" — the demo answers only "what did THIS trace look like", which is precisely the question it exists to pose.

                                And the cost honesty this topic keeps promising: SAMPLING. Tracing everything, always, means allocating spans for every request in exchange for information you will almost never read — so production samples (Boot's default is 10% of traces; this app pins 100% in the yaml because a teaching app wants every tour run traced). The subtle choice is WHICH traces to keep: head-based sampling (decide at trace start, the default) is cheap but blind — it samples the fast requests and misses the slow ones you wanted; tail-based sampling (decide at trace END, keep it if slow or erroring) keeps the interesting traces but buffers every trace until it ends. That trade is why tracing platforms exist as products rather than config lines. One more honest note: ids cross THREAD boundaries only where context is explicitly propagated (micrometer's context-propagation for pool hand-offs) — an exec'd Runnable starts fresh unless you carry the context yourself, which is exactly how "the trace ends at the async boundary" becomes a support ticket.""")),
                section("alerting-on-what-matters", "Alerting on what matters",
                        "Alert on symptoms a human can act on — error rate, latency percentiles, saturation — not on causes you guessed in advance. Alert fatigue is the real enemy.",
                        text("am:symptoms", """
                                You have logs, metrics, and traces — the last pillar is deciding when they should WAKE SOMEBODY UP. The rule that survives contact with reality: alert on SYMPTOMS, not causes. A symptom is something a user is experiencing right now: the error RATE is up (real failures per second, computed from statuses or error-level logs — a rate, not a count, so restarts do not fake it); latency PERCENTILES are up (p99 over a window crossing a budget you set — users are waiting); SATURATION is climbing (the queue gauge trending to its ceiling, the pool near max, disk near full — the system is about to start failing DIFFERENTLY). These are page-worthy because each says "users are hurt NOW and a human decision changes the outcome".

                                Causes are the tempting alternative and the classic mistake: alert on "PG unreachable" or "retry count high" or "instance of FooException seen". You guessed the causes in advance and the outage you get will be the one you did not guess; the SAME symptom fires five cause alerts (which do you read first?); and half the time the cause fires while fallbacks are working and NOBODY is hurt — a page for a nothing. Causes belong in DASHBOARDS, where the person already paged by the symptom goes hunting.

                                The burn-down honesty: every alert spends the on-call's attention, and attention does not refill. An alert that fires weekly and gets ignored has trained the team to ignore the CHANNEL — when the real one fires, it dies in the same muted channel. The discipline: if a page cannot name the action ("what do I DO when this fires?"), it is a dashboard, not a page; review monthly and demote anything that fired without action. This app gives you the raw material for the first draft (error statuses in the completion lines and the timer's outcome tags; latency percentiles in the timer; the queue and ring gauges as saturation seeds) — wiring them to a pager is deliberately out of scope, because the pager policy is yours, not the framework's.""")),
                section("process-level-safety-nets", "Process-level safety nets",
                        "Below the request path: the uncaught-exception net, exit codes a supervisor can read, and the honest limits of restart-as-treatment.",
                        text("pn:nets", """
                                Everything above watches REQUESTS. But a process is more than its request path: background threads fire-and-forget work, schedulers tick, and any of them can die in ways no @ControllerAdvice will ever see. The first net this app installs is topic 30's, cross-quoted here because it IS the observability floor for everything below the request path:"""),
                        code("pn:uncaught", "java",
                                "guide/domain/errors/UncaughtFailureLogger.java — the JVM-wide net: a death nobody caught becomes a legible ERROR, then still a death",
                                "server/src/main/java/guide/domain/errors/UncaughtFailureLogger.java", """
                                public final class UncaughtFailureLogger implements Thread.UncaughtExceptionHandler {

                                    public static UncaughtFailureLogger install() {
                                        Thread.UncaughtExceptionHandler existing = Thread.getDefaultUncaughtExceptionHandler();
                                        if (existing instanceof UncaughtFailureLogger already) {
                                            return already;
                                        }
                                        UncaughtFailureLogger handler = new UncaughtFailureLogger(existing);
                                        Thread.setDefaultUncaughtExceptionHandler(handler);
                                        return handler;
                                    }

                                    @Override
                                    public void uncaughtException(Thread thread, Throwable throwable) {
                                        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                                        log.error("uncaught exception (process net): thread={} requestId={}",
                                                thread.getName(), requestId != null ? requestId : "n/a", throwable);
                                        if (previous != null) {
                                            previous.uncaughtException(thread, throwable);
                                        }
                                    }
                                }"""),
                        text("pn:limits", """
                                Note what the net does NOT do: it does not swallow. After logging, the thread still dies — an uncaught handler cannot rescue anything; it can only make the death LEGIBLE (an ERROR with a request id, not a lost stderr line). Beside it: OOM behavior — the JVM's last gasp depends on which memory ran out (a heap OOM usually throws OutOfMemoryError somewhere catchable and keeps limping; metaspace or native exhaustion can kill the process outright), so production pairs an explicit heap dump path (`-XX:+HeapDumpOnOutOfMemoryError`) with the awareness that the dump IS a pause plus disk. And EXIT CODES — the process's last log line, written for a supervisor: the JVM's own conventions (1 for a startup failure, 137 for the OOM-killer's SIGKILL) plus Boot's `ExitCodeGenerator` so your shutdowns can SAY why.

                                The caveat that closes the topic's machinery, stated plainly: RESTART IS NOT A STRATEGY. A supervisor restarting a crash-looping process is a stalling tactic — each restart resets the counters you were watching and hides the accumulating cause under a fresh process. Restarts are right exactly when the process state itself is the problem (a leaked resource, a corrupted cache, a wedged pool) and wrong for a load problem, a bug, or a dependency outage, where they just fast-forward the log. The mature pattern is what the probes section set up: let the PLATFORM decide from evidence (liveness says restart, readiness says reroute), let the nets make every death legible, and treat each restart as an incident to explain — not a treatment to schedule.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic, curled: the ring, the emitter, the meters, the trace, and the actuator endpoints they hang from.",
                        text("tc:intro", """
                                Every screen in this topic has a terminal twin. The tour assumes the server on localhost:8080 (dev keeps the console readable; the ring serves fields in every profile). Lines are abridged for the page; the ids will differ every run — that being rather the point."""),
                        text("tc:tour", """
                                Start with the ring — the same events the viewer renders, newest first, filterable and capped:"""),
                        code("tc:logs", "bash",
                                "the log ring and the emitter, from a terminal",
                                null, """
                                curl -s 'localhost:8080/api/demo/obs/logs?limit=3'
                                # {"capacity":500,"size":42,"events":[{"timestampMs":1760000000114,
                                #   "level":"INFO","logger":"guide.http","message":"GET /api/demo/obs/logs 200 3ms",
                                #   "requestId":"7f3a-…","traceId":"4bf9-…"} …]}

                                curl -s 'localhost:8080/api/demo/obs/logs/emit?level=warn'   # one deliberate warn
                                curl -s 'localhost:8080/api/demo/obs/logs?level=WARN&limit=5' # find it back, fielded"""),
                        text("tc:metrics", """
                                The meters, curated, then the same guide_* series in raw exposition — the exact bytes a scraper pulls:"""),
                        code("tc:prom", "bash",
                                "meters from the summary endpoint and from the prometheus endpoint",
                                null, """
                                curl -s localhost:8080/api/demo/obs/metrics/summary | head -c 600
                                # {"meters":[{"name":"guide.demo.requests","type":"timer",
                                #   "tags":{"outcome":"success"},"values":{"count":3.0,"meanMs":4.1, …}} …]}

                                curl -s localhost:8080/actuator/prometheus | grep '^guide_'
                                # guide_jobs_outcomes_total{outcome="succeeded"} 1.0
                                # guide_obs_ring_size 12.0
                                # guide_posts_total 1.0   ← _created is a reserved prometheus suffix"""),
                        text("tc:trace", """
                                Then the trace — one request that crosses an HTTP hop and comes back with its own evidence: the id, the spans with timings, and the log lines that carried the same id. Send your OWN traceparent to watch the join from the outside; the completion line for the hop itself appears in the ring afterwards, MDC-joined:"""),
                        code("tc:trace-curl", "bash",
                                "the traced flow, plus driving it with your own trace id",
                                null, """
                                curl -s localhost:8080/api/demo/obs/trace | head -c 700
                                # {"traceId":"4bf9…","traceparentSent":"00-4bf9…-00f0…-01",
                                #  "hop":{"traceparentSeen":"00-4bf9…-…","traceIdFromMdc":"4bf9…",
                                #    "facts":{"postId":"…","jobId":"obs-…","outcome":"succeeded" …}},
                                #  "spans":[…],"logs":[…]}

                                # drive it yourself: your trace id in, the same id out — propagation, hand-made
                                curl -s -H 'traceparent: 00-11111111111111111111111111111111-2222222222222222-01' \\
                                     localhost:8080/api/demo/obs/trace/hop | head -c 400

                                # and the probes the platform would poll
                                curl -s localhost:8080/actuator/health/liveness
                                curl -s localhost:8080/actuator/health/readiness""")));
    }
}
