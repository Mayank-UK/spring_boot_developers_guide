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
 * Topic 39 — Reliability. Group platform, order 10. The discipline the
 * whole topic defends: a distributed system is a system in which things
 * you do not control FAIL, SLOW, LIE, and DISAPPEAR — and reliability is
 * designing for that list ON PURPOSE. Timeouts first (a call without a
 * budget is a hang), retries second (polite, bounded, idempotent), the
 * circuit breaker third (collective memory that stops calling the dead),
 * degradation, bulkheads and shedding (keep one failure from becoming
 * every failure), chaos (break it on purpose, in a blast radius you
 * choose), and races (the bugs that only happen on Tuesdays). The live
 * demo (reliability:breaker) runs the whole stack deterministically on a
 * VIRTUAL clock — every timeline asserted in its tests, zero sleeps.
 */
public final class ReliabilityTopic {

    private ReliabilityTopic() {
    }

    public static Topic topic() {
        return new Topic("reliability", "Reliability",
                "Design for the failure list on purpose: timeouts before hangs, retries with "
                        + "backoff and idempotency, a Resilience4j circuit breaker you can watch trip and "
                        + "recover in a live deterministic theater, graceful degradation, bulkheads and "
                        + "shedding, chaos you choose, and the races that only happen on Tuesdays.",
                TopicGroupId.PLATFORM, 10,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("distributed-failure", "Distributed failure: everything fails, eventually",
                        "The moment a second process joins, failure stops being an event and becomes a spectrum — and code that assumed one machine starts lying.",
                        text("df:partial", """
                                Every topic before this one could quietly assume the thing it talks to WORKS: the database answers, the cache hits, the upstream API returns. This is the topic where that assumption dies. The moment your process calls ANOTHER process — a database, a cache, another team's HTTP service — failure stops being the binary crash you know from single-machine programming and becomes a SPECTRUM: the dependency can refuse (connection reset), hang (accept your bytes and never answer), SLOW DOWN (answer in thirty seconds what it answered in fifty milliseconds yesterday), LIE (return a 200 with yesterday's data, or garbage), DISAPPEAR mid-request (the pod was rescheduled), or answer you TWICE (the timeout fired, you retried, both arrived). The old distributed-computing fallacies list said it first: the network is NOT reliable, latency is NOT zero, bandwidth is NOT infinite, and the administrator is not you. Everything in this topic is a response to that list.

                                The property that makes the spectrum dangerous is PARTIAL FAILURE. In-process, a called function succeeds or throws — one machine, one fate. Across the wire, the middle of an operation is where certainty ends: you sent the request and do not know if it arrived; the dependency started work and you do not know if it finished; YOUR timeout fired and you do not know if the other side committed. A payment retried in that uncertainty is the double-charge; a webhook redelivered is the duplicate shipment. No amount of code on YOUR side can see through the middle — which is why the reliable systems you admire are not the ones that prevented partial failure (nobody does), but the ones that made every outcome of it EXPLICIT: timeouts that bound the unknown, retries that assume duplicates, idempotency that makes duplicates harmless, and breakers that stop pouring requests into the hole.

                                This guide practices what it teaches: every demo endpoint can be degraded at runtime through the app-wide scenario rail — a runtime-switchable state (fake/live data mode plus a failure-injection flag) that demo endpoints read and tests flip. Its patch path is worth quoting now because the whole topic keeps returning to it — the chaos section drives it, and the reliability demo's downstream listens to it:"""),
                        code("df:rail", "java",
                                "guide/core/scenario/ScenarioStore.java — the app-wide failure rail, flipped at runtime by PATCH /api/demo/scenario",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                public synchronized ScenarioState apply(ScenarioPatch patch) {
                                    Objects.requireNonNull(patch, "patch");
                                    ScenarioState current = state;
                                    ScenarioState next = new ScenarioState(
                                            patch.dataMode() != null ? patch.dataMode() : current.dataMode(),
                                            patch.injectFailure() != null ? patch.injectFailure() : current.injectFailure());
                                    state = next;
                                    return next;
                                }"""),
                        text("df:verdict", """
                                Read the shape, not the fields: a volatile-but-latched state, partial updates (null means "leave it alone"), and the whole thing guarded so a patch either lands or does not. That is a small, honest model of what production failure injection looks like — a switch somebody (a chaos tool, an operator, a test) can throw WITHOUT redeploying anything. The rest of this topic walks the failure list in the order the industry learned to fight it, which is also the order of escalation in a real incident: give every call a TIME BUDGET (timeouts), ask again POLITELY when the failure looks transient (retries), remember failures COLLECTIVELY so you stop asking at all (the circuit breaker), answer with SOMETHING when the real thing is gone (degradation), keep one dependency's outage from consuming every thread you own (bulkheads and shedding), rehearse all of it on purpose (chaos) — and accept that even with all of it, concurrency will still occasionally hand you a bug that only reproduces on Tuesdays (races).""")),
                section("timeouts", "Timeouts: every call needs a budget",
                        "A call without a timeout is not a call — it is a thread donation to someone else's outage. The budget is the first reliability decision, and it belongs to the caller.",
                        text("t:budget", """
                                The default timeout of every network client ever shipped is INFINITE, and that default is the single most reliable way to turn somebody else's bad day into your outage. Walk the failure: the downstream slows to a crawl; each of your request threads blocks on a socket that will answer EVENTUALLY, maybe; your thread pool fills with waiters; new requests queue behind them; the health checks time out too; the orchestrator kills the pod — the dependency that was merely SLOW has now made your service UNAVAILABLE, and every service that calls you inherits the same arithmetic. A hang is not a milder failure than an error; it is a WORSE one, because it holds resources hostage while it fails. The timeout is the line you draw: past this many milliseconds, whatever is on the other side stops being slow and STARTS BEING GONE.

                                Two decisions make a budget honest. First, WHOSE number it is: the timeout is the CALLER's policy, set from the caller's latency requirements — never a property the downstream advertises. A checkout flow with a two-second p95 budget gives its payment call maybe one second, regardless of what the payment service thinks its normal is. Second, WHERE the line sits: too tight and you convert a healthy-but-warm dependency into an error page (set it somewhere past the dependency's honest p99, not its mean — the performance topic's medians lesson again); too loose and you have only renamed "infinite" to "large." And set it EVERYWHERE, out loud: the HTTP client's connect and response timeouts, the JDBC query timeout, the Redis command timeout — Spring Boot exposes each as a property (spring.jdbc.template.query-timeout, spring.data.redis.timeout, and the pooling client's own knobs), and the deployment topic's graceful-shutdown budget is this same discipline applied to your OWN shutdown.

                                This topic's live demo models the whole decision deterministically, which is worth a paragraph on its own: real latency in a teaching demo would make every timeline wall-clock-dependent and every test flaky. So the demo's downstream theater is a SIMULATOR with four personalities, and its clock is VIRTUAL — latency is a number the theater reports, and the caller books it against a settable instant. No thread ever sleeps; the exact millisecond arithmetic you will watch in the demo is asserted byte-for-byte in its tests. The budget itself lives in the retry policy:"""),
                        code("t:policy", "java",
                                "guide/domain/reliability/RetryPolicy.java — the budget: three attempts, 500ms each",
                                "server/src/main/java/guide/domain/reliability/RetryPolicy.java", """
                                public static final int MAX_ATTEMPTS = 3;
                                public static final Duration ATTEMPT_TIMEOUT = Duration.ofMillis(500);

                                // …

                                public Duration billed(Duration wouldTake) {
                                    return wouldTake.compareTo(ATTEMPT_TIMEOUT) > 0 ? ATTEMPT_TIMEOUT : wouldTake;
                                }"""),
                        text("t:theater", """
                                The `billed` method is the timeout's whole semantics in two lines: an attempt that would take LONGER than the budget is abandoned AT the budget — the caller books 500ms, not the 1500ms the downstream would have taken — exactly like a real client that gives up on the socket and moves on. What the downstream would have said is a question the caller deliberately stops asking. The theater that answers (or refuses to) is a small switch with four personalities, plus the app-wide failure rail wired in as a plain BooleanSupplier — the chaos section returns to that door:"""),
                        code("t:theater-code", "java",
                                "guide/domain/reliability/DownstreamTheater.java — four downstream personalities, deterministic by construction",
                                "server/src/main/java/guide/domain/reliability/DownstreamTheater.java", """
                                public record AttemptOutcome(boolean success, Duration wouldTake, String detail) {
                                }

                                // …

                                public AttemptOutcome attempt() {
                                    int attemptNumber = attemptCounter.incrementAndGet();
                                    if (globalFailureRail.getAsBoolean()) {
                                        return new AttemptOutcome(false, Duration.ofMillis(40),
                                "injected by the global failure rail (injectFailure=true)");
                                    }
                                    return switch (mode) {
                                        case HEALTHY -> new AttemptOutcome(true, Duration.ofMillis(150),
                                "200 OK in " + 150 + "ms");
                                        case FLAKY -> attemptNumber % 2 == 1
                                ? new AttemptOutcome(false, Duration.ofMillis(200), "503 from upstream (transient)")
                                : new AttemptOutcome(true, Duration.ofMillis(120), "200 OK — the flake passed");
                                        case DOWN -> new AttemptOutcome(false, Duration.ofMillis(30),
                                "connect refused — the downstream is gone");
                                        case SLOW -> new AttemptOutcome(true, Duration.ofMillis(1500),
                                "would answer, but 1500ms exceeds the 500ms budget");
                                    };
                                }"""),
                        text("t:verdict", """
                                Read the four personalities as the four failure shapes from the opening section, compressed: HEALTHY (150ms, under budget), FLAKY (fails every ODD attempt — transient noise), DOWN (fails fast — the crash shape), SLOW (would succeed, but 1500ms against a 500ms budget — the hang shape, and notice the timeout fires even though the answer was coming). One nuance the demo will make visible: a timeout is NOT evidence the downstream failed. The SLOW mode's attempt would have returned a perfectly good answer, one second later — the timeout fired because the CALLER'S budget, not the downstream's correctness, ran out. Distinguishing "too slow for me" from "broken" is exactly why the next layer exists: retries re-ask what looked transient, and the breaker counts what kept failing. The budget, the ask-again policy, and the memory — in that order.""")),
                section("retries", "Retries: ask again, politely",
                        "A retry is a bet that the failure was transient — a bet placed with the downstream's health, your threads, and possibly your user's money. Politeness is the engineering discipline.",
                        text("r:why", """
                                Retrying feels free — the call failed, so try it again — and that instinct is where retry storms are born. Three rules separate the polite from the pathological. RULE ONE, CLASSIFY BEFORE RETRYING: retry only failures that plausibly WERE transient — a connection reset, a 503, a timeout. A 400 (your request is wrong), a 401 (your credentials are wrong), or a 404 will fail identically forever; retrying them is just burning the downstream's CPU to feel busy. RULE TWO, BACK OFF: immediate retries hammer a struggling dependency in the exact second it is trying to recover; the polite curve waits, and grows the wait with each failure — exponential with a CAP and, in a system with many callers, JITTER (a random share of the delay added on top), because a thousand clients that all retry at exactly t+1s are a synchronized DDoS they built by accident, the thundering herd. RULE THREE, THE IDEMPOTENCY PRECONDITION: only retry what is safe to do twice. An idempotent operation (GET, PUT of a fixed value, DELETE) can be retried freely; a POST that creates or charges must carry an idempotency key the server deduplicates — otherwise the retry that saved your availability just double-charged a customer.

                                This app already runs one production-shaped retry engine — the webhooks delivery engine from topic 14, where every failed delivery is re-attempted on a schedule until the dead-letter queue takes over. Its backoff curve is the cleanest six lines of reliability policy in the codebase:"""),
                        code("r:backoff", "java",
                                "guide/domain/webhooks/DeliveryEngine.java — the polite curve: exponential, capped, jittered",
                                "server/src/main/java/guide/domain/webhooks/DeliveryEngine.java", """
                                /** backoff = 1s, 2s, 4s, then capped at 8s (and jitter on top, up to +100%). */
                                static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
                                static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

                                // …

                                static Duration backoffAfter(int failedAttempts) {
                                    if (failedAttempts < 1) {
                                        throw new IllegalArgumentException("backoff starts after the first failure");
                                    }
                                    long exponent = Math.min(failedAttempts - 1, 3);
                                    Duration delay = BASE_BACKOFF.multipliedBy(1L << exponent);
                                    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
                                }"""),
                        text("r:loop", """
                                Exponential growth shows respect for a struggling consumer (each extra second is another second it gets to drain its queue); the cap shows respect for your OWN freshness budget (a delivery nobody retries for nine hours is not reliability, it is archaeology); the jitter (injected in that engine as a DoubleSupplier seam, so tests pin exact schedules by passing zero) de-synchronizes competing senders. The reliability demo's retry policy runs the same curve — deliberately, so both topics teach one shape. Its caller wraps the whole ask-again loop around the theater, and every temporal claim in it is booked against the virtual clock:"""),
                        code("r:caller-loop", "java",
                                "guide/domain/reliability/ResilientCaller.java — the retry loop: attempt, timeout, backoff — all virtual time",
                                "server/src/main/java/guide/domain/reliability/ResilientCaller.java", """
                                private AttemptsRun runAttempts() {
                                    List<AttemptReport> attempts = new ArrayList<>();
                                    long callStartedAt = time.elapsedMs();
                                    for (int attempt = 1; attempt <= RetryPolicy.MAX_ATTEMPTS; attempt++) {
                                        long startedAt = time.elapsedMs();
                                        DownstreamTheater.AttemptOutcome outcome = theater.attempt();
                                        boolean timedOut = outcome.wouldTake().compareTo(RetryPolicy.ATTEMPT_TIMEOUT) > 0;
                                        time.advance(policy.billed(outcome.wouldTake()));
                                        attempts.add(new AttemptReport(attempt,
                                                timedOut ? "timed_out" : outcome.success() ? "success" : "failed",
                                                startedAt, time.elapsedMs(), outcome.detail()));
                                        if (!timedOut && outcome.success()) {
                                            return new AttemptsRun("SUCCESS", List.copyOf(attempts),
                                                    time.elapsedMs() - callStartedAt);
                                        }
                                        if (policy.canRetry(attempt)) {
                                            time.advance(RetryPolicy.backoffAfter(attempt));
                                        }
                                    }
                                    // … the exhausted loop throws, so the breaker can record the failure
                                }"""),
                        text("r:idempotency", """
                                Walk one FLAKY call through that loop and watch the demo's arithmetic: attempt one fails at t=200ms (the transient 503), the backoff books a second of waiting, attempt two succeeds at t=1320ms — the CALL succeeded and cost 1.32 virtual seconds, and (the punchline the breaker section will need) the caller's caller never knew anything happened. Retries ABSORB transient failure; that is their job. But every retry is also a duplicate-in-waiting — the request may already be executing on the other side — which is why the idempotency precondition is not optional. The messaging topic built the pattern in full: its consumer turns every delivery into an applied-or-skipped decision in ONE place, so redeliveries (from retries, from worker crashes, from XAUTOCLAIM) are recognized rather than double-applied:"""),
                        code("r:idempotent-consumer", "java",
                                "guide/domain/messaging/IdempotentConsumer.java — the twin defense: make duplicates harmless",
                                "server/src/main/java/guide/domain/messaging/IdempotentConsumer.java", """
                                public Outcome process(EventStream.Delivery delivery) {
                                    boolean first = counters.record(delivery.event());
                                    return new Outcome(delivery.id(), delivery.event(), !first);
                                }"""),
                        text("r:verdict", """
                                Three production habits close the section. BOUND THE TOTAL: cap attempts AND wall-clock duration (three attempts with 1s/2s backoffs inside a 500ms-timeout call can legitimately burn seconds — make sure that fits the caller's budget from the timeouts section, and remember the deployment topic's shutdown budget has to outlive your longest in-flight retry). RETRY BUDGETS AT SCALE: a fleet retrying at 10% of traffic triples the load exactly when the dependency is dying — retry rate is a METRIC (the observability topic's meters), and some systems cap fleet-wide retries with token buckets. And TEST THE SCHEDULE, NOT THE LUCK: the demo's tests assert the exact 1320ms and the exact 4500ms slow-mode timeline because the clock is virtual — a test that sleeps real seconds to "wait for the backoff" is a flaky test wearing a lab coat. What retries cannot fix is persistence: when the failure is not transient at all, asking three times politely is still asking. For that, memory — the circuit breaker.""")),
                section("circuit-breaker", "Circuit breakers: stop calling the dead",
                        "Retries alone amplify outages. The breaker adds collective memory: recent outcomes decide whether to call at all — CLOSED, OPEN, and the HALF_OPEN probe that decides recovery.",
                        text("cb:why", """
                                Here is the arithmetic retries cannot escape: a downstream that is DOWN, called by a service with a 3-attempt retry policy, receives THREE requests per user request — every one doomed, every one holding a caller thread for the full timeout-and-backoff span. Your service's throughput collapses to a third, its thread pool fills with waiters retrying a corpse, and the dying downstream — which might otherwise have recovered — receives three times the traffic in its death throes. The missing ingredient is MEMORY: the twentieth consecutive failure carries information the nineteenth did not, and a caller that treats every request as a fresh optimist wastes everyone's resources learning it again. The circuit breaker (named for the electrical fuse box, and doing the same job) is that memory: it records recent call outcomes in a sliding window, and when the failure rate crosses a threshold it OPENS — for a configured wait, calls are refused BEFORE they leave, with a fast, cheap, typed error instead of a slow, expensive timeout. The caller's threads come back instantly; the downstream gets silence, which is what a recovering system needs.

                                Three states, no more: CLOSED (normal — calls pass, outcomes recorded; the name means the circuit is closed, current flows), OPEN (tripped — calls rejected at the door), and HALF_OPEN (the wait has elapsed, or an operator forced it — a small number of PROBE calls are allowed through; success closes the circuit, failure re-opens it). The genius is in the last state: recovery is tested with bounded blast radius, not with the full firehose. This is the exact pattern Spring's ecosystem standardized — Resilience4j — and this topic uses it for real: the dependency landed in the pom, verified against Boot 4.1.1 (it drags no Boot 3 jars; its autoconfiguration boots cleanly beside actuator and micrometer):"""),
                        code("cb:pom", "xml",
                                "server/pom.xml — resilience4j-spring-boot3, the line the whole demo stands on",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>io.github.resilience4j</groupId>
                                    <artifactId>resilience4j-spring-boot3</artifactId>
                                    <version>2.3.0</version>
                                </dependency>"""),
                        text("cb:knobs", """
                                The starter brings three integration shapes: ANNOTATIONS (@CircuitBreaker on a bean method, applied through the aspectj starter this app already carries), YAML CONFIGURATION (resilience4j.circuitbreaker.instances.<name>.* keys for thresholds and waits, bound by its autoconfiguration), and the PROGRAMMATIC API — breaker instances you adapt calls through yourself. This demo takes the third shape one architectural step further, and the step is worth stealing for every third-party machine you ever wrap: the DOMAIN owns a port — BreakerPort, with a call-through, a state read, a snapshot of counters, and the two ops rails — that speaks only domain vocabulary; an ADAPTER under data/ owns the Resilience4j instance, its five knobs, and the translation of the one library exception that matters into the domain's own BreakerOpenException; and the composition root wires them in a single bean. That is topic 32's dependency rule applied to a library instead of a layer — the use cases never learn whether the breaker is Resilience4j, a hand-rolled state machine, or a test stub, and each of those becomes a one-line wiring change. The wiring also behaves IDENTICALLY under the full app and under a @WebMvcTest slice, because the adapter is a plain object with no context-shaped failure modes. First the wiring half:"""),
                        code("cb:config", "java",
                                "guide/config/ReliabilityDemoConfig.java — the composition root: the port is one bean, the scenario rail one lambda",
                                "server/src/main/java/guide/config/ReliabilityDemoConfig.java", """
                                @Bean
                                DownstreamTheater downstreamTheater(ScenarioStore scenarioStore) {
                                    return new DownstreamTheater(() -> scenarioStore.get().injectFailure());
                                }

                                // …

                                /** The breaker BEHIND the domain's port — the only bean that knows r4j exists. */
                                @Bean
                                BreakerPort reliabilityBreaker() {
                                    return new Resilience4jBreakerAdapter();
                                }"""),
                        text("cb:adapter-intro", """
                                And the machinery half — the only class in the topic that imports anything from io.github.resilience4j. The five knobs live here, in one constructor, as decisions rather than settings, and the exception translation happens AT the boundary so the domain never sees a library type:"""),
                        code("cb:adapter", "java",
                                "guide/data/reliability/Resilience4jBreakerAdapter.java — owns the instance, the knobs, and the exception translation",
                                "server/src/main/java/guide/data/reliability/Resilience4jBreakerAdapter.java", """
                                public Resilience4jBreakerAdapter() {
                                    this.breaker = CircuitBreaker.of(BREAKER_NAME, CircuitBreakerConfig.custom()
                                            .failureRateThreshold(50f)
                                            .minimumNumberOfCalls(4)
                                            .waitDurationInOpenState(WAIT_IN_OPEN)
                                            .permittedNumberOfCallsInHalfOpenState(1)
                                            .slidingWindowSize(10)
                                            .build());
                                }

                                // …

                                @Override
                                public <T> T execute(Supplier<T> call) {
                                    try {
                                        return breaker.decorateSupplier(call).get();
                                    } catch (CallNotPermittedException rejected) {
                                        throw new BreakerOpenException(rejected);
                                    }
                                }"""),
                        text("cb:stack", """
                                Five decisions in order: FAILURE-RATE THRESHOLD 50% (over the recent window, half the calls failing means the dependency is sick, not unlucky); MINIMUM CALLS 4 (do not judge a dependency on two data points — the failure rate is only computed once the window holds enough calls, so one bad request at 2 a.m. cannot trip a healthy service); SLIDING WINDOW 10 (the memory horizon — the last ten outcomes, count-based); WAIT 10 SECONDS in OPEN (long enough for a restart or a GC storm to pass, short enough that recovery is tested soon); and ONE permitted probe in HALF_OPEN (a single call decides — success closes, failure re-opens: minimum blast radius). Composition order matters as much as the knobs: the BREAKER wraps the RETRY loop, not the other way around, because the breaker judges CALLS (what your user experienced) while retries are one call's internal implementation detail. Nested the other way, every user request could retry its way through an open breaker. The caller makes the sandwich explicit:"""),
                        code("cb:caller", "java",
                                "guide/domain/reliability/ResilientCaller.java — breaker outside, retries inside; rejection is cheap and domain-typed",
                                "server/src/main/java/guide/domain/reliability/ResilientCaller.java", """
                                public synchronized CallReport call() {
                                    String before = breaker.state();
                                    try {
                                        AttemptsRun run = breaker.execute(this::runAttempts);
                                        return remember(frame(run, before), before);
                                    } catch (BreakerOpenException rejected) {
                                        CallReport report = new CallReport("REJECTED_BY_BREAKER", before,
                                                breaker.state(), List.of(), 0L, 0);
                                        return remember(report, before);
                                    } catch (CallExhaustedException exhausted) {
                                        return remember(frame(exhausted.run, before), before);
                                    }
                                }"""),
                        demo("cb:breaker-demo", "reliability:breaker", "Trip and recover a real circuit breaker",
                                "A deterministic breaker theater on a virtual clock. Read the live status (state, window metrics, the server's own reading of what the numbers mean); dial the downstream's personality — healthy, flaky, down, slow — and fire calls: each answer carries the exact millisecond timeline of its attempts (latency, backoff, timeout). Trip the breaker with four failing calls, watch new calls REJECTED AT THE DOOR with zero downstream attempts, force the HALF_OPEN probe, and close the circuit again with one healthy call. The global injectFailure rail (PATCH /api/demo/scenario) fails every attempt regardless of the dial."),
                        text("cb:client-half", """
                                What you are watching in that panel: the FLAKY dial demonstrates retry-brokered transparency (calls succeed at t=1320ms; the breaker's window records only successes — it never knew). The DOWN dial ×4 demonstrates the trip (failure rate 100% over the 4-call minimum → OPEN, with the panel reporting the opened-at instant and when the wait ends). Calls against the OPEN breaker come back in ZERO milliseconds with ZERO downstream attempts — the cheapest failure you will ever ship. The probe rail exists because Resilience4j's own OPEN→HALF_OPEN timer runs on the REAL clock while the demo's timeline is virtual — forcing the transition manually is the same call a production operator endpoint would make, and the only door that keeps the demo deterministic. The status endpoint's narration is GENERATED from the live breaker, the same anti-drift rule the perf topic applies to its compression posture:"""),
                        code("cb:reading", "java",
                                "guide/domain/reliability/ReadBreakerStatus.java — the narration is built from the live snapshot, so it cannot lie",
                                "server/src/main/java/guide/domain/reliability/ReadBreakerStatus.java", """
                                switch (state) {
                                    case "OPEN" -> reading.add("OPEN since t=" + openedAtMs.orElse(0L) + "ms (virtual) — calls are rejected at the door; the wait window ends at t="
                                            + probeEligibleAtMs + "ms, or force the half-open probe now.");
                                    case "HALF_OPEN" -> reading.add("HALF_OPEN — the next " + snapshot.permittedInHalfOpen()
                                            + " probe call(s) decide: success closes the breaker, failure re-opens it.");
                                    default -> reading.add("CLOSED — calls pass through; the window holds " + successes
                                            + " success(es) and " + failures + " failure(s) and starts judging at "
                                            + snapshot.minimumNumberOfCalls() + " recorded calls.");
                                }"""),
                        text("cb:verdict", """
                                The client half of the demo stays boring on purpose — every button is one fetch, and the panel renders only what the server reported:"""),
                        code("cb:client", "ts",
                                "client/src/ui/demos/reliability/breaker_demo.tsx — one POST, the report paints itself",
                                "client/src/ui/demos/reliability/breaker_demo.tsx", """
                                const callDownstream = useCallback(() => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () =>
                                    parseCallReport(await requestJson(config, '/demo/reliability/call', 'POST')),
                                  ).then((result) => {
                                    if (result.ok) {
                                      setCallReport(result.value);
                                      refreshStatus();
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                }, [config, refreshStatus]);"""),
                        text("cb:close", """
                                Beyond this one instance, Resilience4j ships the whole family this topic has been naming piecewise: @CircuitBreaker annotations with fallback methods, a TimeLimiter module (the async twin of the per-attempt budget), Bulkhead (next section, as a first-class primitive), RateLimiter, and micrometer metrics bound automatically for the autoconfigured registry — the observability topic's meters are how a fleet answers "which breakers are open RIGHT NOW" without opening a terminal. Two honest warnings before moving on. A breaker is not a LAW, it is a POLICY: tune thresholds from measured failure rates, not from this file's demo numbers, and expect to retune. And a breaker without degradation is only half a design — rejecting calls fast still leaves the caller with NOTHING; what the caller DOES with the rejection is the next section's whole subject.""")),
                section("degradation", "Graceful degradation: answer with less",
                        "Fail fast is only half the sentence. The other half is what the user sees: a ladder from fresh data down to an honest error, decided in advance — per feature, not per stack trace.",
                        text("dg:why", """
                                Degradation is the discipline of answering with SOMETHING when the real answer is unavailable, and its opposite is the failure mode everyone has met: a page whose entire content area reads "unexpected error" because one widget's API is down. The binary lie — full answer or 500 — is a DESIGN choice, and it was made accidentally. The reliable alternative is a LADDER, walked in order: the FRESH answer (dependency healthy); the CACHED answer, marked stale (dependency slow or tripped — the caching topic's TTL hierarchy pays off here); the REDUCED answer (the parts of the page that need nothing external — your own static catalog, the user's own data); the DEFAULT answer (a known-safe fallback value the product signed off on); and only then, the HONEST error — typed, specific, and ideally partial ("comments are unavailable; the article is fine"). Every rung the product can support is availability the dependency outage cannot take away.

                                The breaker is what makes the ladder TRIGGERABLE: an OPEN breaker is a fast, cheap, PREDICTABLE signal that the fresh rung is gone — which is why breaker + fallback is one pattern, not two (Resilience4j's annotation API literally takes the fallback method beside the breaker name). But rung-choosing is a PRODUCT decision disguised as an engineering one: which features degrade silently, which surface a banner, which are load-bearing and should fail the whole request — decide in a design conversation, encode as policy, and the incident becomes a smaller incident. This app's own small-but-real version of the ladder is its per-area data modes: every data-backed demo reads its area's mode through one seam, with a global mode underneath — flip an area to fake and that screen degrades to seeded data WITHOUT touching any other screen:"""),
                        code("dg:scenario", "java",
                                "guide/core/scenario/ScenarioStore.java — per-area degradation policy, one seam, runtime-switchable",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                public ScenarioDataMode dataModeFor(String area) {
                                    Objects.requireNonNull(area, "area");
                                    ScenarioDataMode override = areaModes.get(area);
                                    return override != null ? override : state.dataMode();
                                }"""),
                        text("dg:rules", """
                                Three rules keep a ladder honest. THE FALLBACK MUST BE CHEAPER THAN THE CALL IT REPLACES — a fallback that hits another dependency chains your outages together (the fallback for the recommendation service must not be the search service if both live in the same cluster); the best fallbacks are local: cached, static, or computed. THE FALLBACK MUST NOT FAIL — it gets no dependencies and no exciting logic; if your fallback can throw, you have not built a rung, you have built a second tightrope. And DEGRADE AT BOOT AS DELIBERATELY AS AT RUNTIME — this app's optional-service discipline is degradation's morning routine: redis and mongo adapters are @Lazy, their health indicators are opted out, and the process boots and serves with those services absent rather than dying in a startup check (the redis topic tells that story in full). One more parallel worth noticing: the deployment topic's graceful shutdown is degradation pointed at yourself — stop accepting NEW work, finish the old, and go quiet like a professional. The ladder's last rung, the honest error, is where this topic's opening promise lands: partial failure made EXPLICIT. What the ladder cannot fix is arithmetic — when the requests keep ARRIVING faster than any rung can answer them. That is the next section's problem: capacity, compartments, and the courage to refuse.""")),
                section("bulkheads", "Bulkheads and load shedding: refuse work you cannot do",
                        "A ship's hull has compartments so one breach is not a sinking. Your thread pool, connection pool, and queues are the compartments — and shedding is refusing early instead of drowning slowly.",
                        text("bh:why", """
                                The shared-resource failure needs its own arithmetic, because it is the one that turns LOCAL slowness into GLOBAL unavailability. Your web tier has a fixed budget of worker threads (platform threads in a classic pool, or carrier capacity under virtual threads) and every dependency that is slow is holding some of them. One slow downstream — even WITH timeouts — pins threads for up to the timeout span per request; enough concurrent requests and the pool is entirely waiters-for-the-slow-thing; requests for the HEALTHY parts of your app queue behind them; your service is now down "because" of one dependency. The bulkhead — the naval architecture word for a compartment wall — is the fix: give each dependency its OWN bounded slice of concurrency (a dedicated pool, or a Semaphore of N permits around the calls) so its worst day consumes its compartment and nothing else. The performance topic already quoted this app's most important bulkhead without using the word: HikariCP's maximum-pool-size is a bulkhead the database wears — eight connections means the database's bad quarter can hold at most eight of this app's threads:"""),
                        code("bh:hikari", "yaml",
                                "server/src/main/resources/application.yml — the pool as bulkhead: 8 connections, by decision",
                                "server/src/main/resources/application.yml", """
                                datasource:
                                  url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}
                                  # … username and password ride the same twelve-factor placeholders
                                  hikari:
                                    maximum-pool-size: 8"""),
                        text("bh:bounded", """
                                The same wall appears at smaller scales all over this codebase, and the discipline is always the same shape: BOUND THE BATCH. The webhooks engine's ticker — the @Scheduled heartbeat that retries due deliveries — does not loop until the queue is empty; it processes a bounded batch per tick, because a tick that runs forever is a thread nobody gets back and a schedule that stops beating:"""),
                        code("bh:tick", "java",
                                "guide/domain/webhooks/DeliveryEngine.java — the batch ceiling: one tick, bounded work, then done",
                                "server/src/main/java/guide/domain/webhooks/DeliveryEngine.java", """
                                /** How many due deliveries one tick may process — the batch ceiling that keeps a tick bounded. */
                                static final int TICK_BATCH = 10;

                                // …

                                public List<WebhookDelivery> attemptNextDue(Instant now) {
                                    List<WebhookDelivery> touched = new ArrayList<>();
                                    for (WebhookDelivery due : store.dueDeliveries(now, TICK_BATCH)) {
                                        touched.add(attempt(due, now));
                                    }
                                    return List.copyOf(touched);
                                }"""),
                        text("bh:shed", """
                                Shedding is the bulkhead's last word applied to the FRONT door: when the work ARRIVING exceeds what the compartments can hold, the honest move is to REFUSE EARLY — a 429 or 503 at the edge costs one packet, while queueing it costs memory, latency, and (worse) a queue that only ever grows (an unbounded queue is a delayed OutOfMemoryError wearing a productivity costume — the performance topic's thread-hygiene rule, restated at system scale). The security topic built shedding at this app's edge with Bucket4j's token bucket — rate limiting per API key — and the two compose: the rate limiter sheds by POLICY (this client is too loud), the bulkhead sheds by CAPACITY (this compartment is full), and both beat the alternative, which is shedding by COLLAPSE. One modern note that matters under virtual threads: cheap threads make the bulkhead MORE necessary, not less — this app will happily mount ten thousand concurrent requests on virtual threads, but the DATABASE still has eight connections and the downstream still has its limits; unbounded request concurrency against bounded resources is a queue, whatever the thread model. Resilience4j ships a Bulkhead module (semaphore- or threadpool-based) and a RateLimiter for exactly these walls, configured beside the breaker you just tripped. Walls, though, are only as good as the drills that test them — which is why reliability engineering eventually points a weapon at itself.""")),
                section("chaos", "Chaos engineering: break it on purpose",
                        "You cannot demonstrate reliability with everything up. Chaos is the experimental method applied to production-shaped systems: hypothesis, blast radius, steady state, abort — written down before the explosion.",
                        text("ch:why", """
                                Every mechanism in this topic — timeouts, retries, breakers, ladders, bulkheads — shares one uncomfortable property: it is UNTESTABLE until it fails, and by then it is too late to discover it was configured wrong. Chaos engineering is the response, and it is less dramatic than the name: systematically injecting the failures you have designed for, into an environment that can survive being wrong, and checking whether the system behaves the way you CLAIM it does. The method is the scientific one, four lines: define the STEADY STATE you expect (p95 under 300ms, error rate under 0.1%, breakers CLOSED); form the HYPOTHESIS ("killing one payments replica keeps the steady state — retries and the breaker will absorb it"); inject the failure INSIDE a blast radius you chose (one replica, one AZ, business hours, eyes on dashboards); and MEASURE what actually happened — then either the hypothesis held (confidence, banked) or it did not (a bug found on purpose, the cheapest kind). What chaos is NOT: an outage with a logo. Injecting failure you have no hypothesis about, at 4 a.m., unattended, is just vandalism with a dashboard.

                                This app's chaos story is intentionally tiny and literal: the scenario rail you met in the first section is a failure-injection switch any test or demo can throw, and the reliability theater wires it straight into its downstream as a pure seam — when the rail is on, every attempt fails no matter what the dial says, exactly like a chaos tool pulling the dependency:"""),
                        code("ch:rail", "java",
                                "guide/domain/reliability/DownstreamTheater.java — the app-wide rail lands in the domain as a pure seam",
                                "server/src/main/java/guide/domain/reliability/DownstreamTheater.java", """
                                private final BooleanSupplier globalFailureRail;

                                // …

                                public DownstreamTheater(BooleanSupplier globalFailureRail) {
                                    this.globalFailureRail = globalFailureRail;
                                }"""),
                        text("ch:scale", """
                                Notice the shape of that seam: the domain takes a BooleanSupplier, not a ScenarioStore — the dependency rule held (domain stays framework-free), and the demo config's one-literal lambda is the whole integration. That same discipline scales to real chaos tooling: production-grade tools (Chaos Monkey for Spring Boot killing beans on a schedule, AWS Fault Injection Simulator, Kubernetes chaos operators yanking pods or injecting network latency) all reduce to the same move — flip a switch you pre-wired, inside a blast radius you pre-chose. Start your first chaos where it is CHEAP: a staging environment with a scripted "kill the cache" run while watching the metrics topic's dashboards; then business-hours production single-instance kills (the deployment topic's replicas and health probes exist to survive exactly this); then grow blast radius only as hypotheses keep holding. And always wire the ABORT: a chaos experiment you cannot stop with one command is not an experiment. The steady-state metrics are what make any of this readable — chaos without observability is just breaking things in the dark. There is one more failure family left, the one no switch throws for you: the bugs that come from CONCURRENCY ITSELF.""")),
                section("races", "Races: the bugs that only happen on Tuesdays",
                        "Two threads, one piece of state, no ordering — and a bug that hides from every test until production traffic aligns the clocks. Virtual threads make concurrency cheap; they do not make it safe.",
                        text("rc:why", """
                                A race condition is a correctness bug caused by ORDERING: the program's answer depends on which thread ran first, and "first" is not a thing you control. The shapes are few and worth naming so you can spot them: CHECK-THEN-ACT (two threads read stock=1, both decide to sell, both sell — the lost update), READ-MODIFY-WRITE over multiple steps without a lock in between (a counter incremented by read-increment-write loses increments exactly when two threads interleave), and VISIBILITY failures that are not about interleaving at all — thread A writes a field, thread B does not see the write because nothing forced the memory to be shared. That last one explains the tools: `volatile` for visibility (every read sees the latest write, no ordering illusions — but volatile does NOT make check-then-act safe), `synchronized`/locks for both visibility AND mutual exclusion, and the atomic classes (AtomicInteger, ConcurrentHashMap) for common shapes done lock-free. The reliability theater uses these deliberately — its clock and state are volatile-latched exactly like the scenario store it learned from:"""),
                        code("rc:visibility", "java",
                                "guide/core/scenario/ScenarioStore.java — visibility made explicit: volatile for the hot read, a map that handles its own",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                private final Map<String, ScenarioDataMode> areaModes = new ConcurrentHashMap<>();
                                private volatile ScenarioState state;"""),
                        text("rc:race-demo", """
                                The database version of the same bug is the one this guide already proved live: topic 17's db:race demo fires genuinely concurrent reservation attempts at one inventory row under different isolation strategies and shows, in numbers, which strategies oversell. Its scoreboard is worth re-reading here because the reliability lens changes what it means — retries make races MORE likely, not less (every retry is another concurrent claimant for the same row; the idempotency keys from the retries section are race armor as much as duplicate armor):"""),
                        code("rc:outcome", "java",
                                "guide/domain/sqldemo/RaceOutcome.java — the oversell verdict, counted honestly",
                                "server/src/main/java/guide/domain/sqldemo/RaceOutcome.java", """
                                public record RaceOutcome(String strategy, int concurrency, int initialStock, int attempted,
                                        int succeeded, int declined, boolean oversold, int finalStock, int finalReserved, String story) {

                                    /** Defensive normalization: a run cannot report more successes than attempts. */
                                    public RaceOutcome {
                                        if (succeeded + declined > attempted) {
                                            throw new IllegalArgumentException("outcome counts exceed attempts");
                                        }
                                    }
                                }"""),
                        text("rc:toolbox", """
                                The fix toolbox, in order of preference: FIX THE ALGORITHM FIRST (a single atomic UPDATE ... SET stock = stock - 1 WHERE stock > 0 makes the lost update impossible — the database does the locking for you; this beats application locks the way structural fixes always beat vigilance). OPTIMISTIC CONCURRENCY for entity-shaped data — the jpa topic's @Version column turns every write into "succeed, or learn someone beat you and retry the whole unit"; conflicts become normal control flow instead of corruption. PESSIMISTIC LOCKS (SELECT ... FOR UPDATE) when contention is low but the cost of a conflict is high, or the unit of work is long. And in-process: synchronized/locks around check-then-act sequences (the reliability caller's call path is synchronized — single-flight, which is both correctness for its counters and, as the bulkheads section noted, a compartment of size one), atomics for counters and flags, and NO sharing where you can avoid it (immutable values — records — and thread-local or confined state are race-proof by construction; the caller's recent-calls list never escapes under a lock for exactly this reason).

                                Two testing notes close the topic's engineering, because races and flakiness are twins. FIRST: concurrency bugs do not reproduce on demand — the race demo exists because firing eight real threads at one row a thousand times beats hoping; write the load, then assert the invariant (no oversell), not the interleaving. SECOND — the thread running through this whole topic — a test that SLEEPS is a test that will flake: this topic's own suite drives a real circuit breaker through OPEN and back with exact millisecond assertions and zero wall-clock waits, because its clock is virtual and its waits are arithmetic. Fake time is not a testing trick; it is the same reliability discipline (make the uncertain DETERMINISTIC) pointed at the test suite. The scaling topic takes the baton from here: reliability is surviving failure at TODAY'S load — capacity is whether tomorrow's load survives at all.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole theater from curl: read the breaker, trip it, get rejected at the door, probe, recover — and throw the chaos rail yourself.",
                        text("tc:intro", """
                                The demo's five endpoints are the same thin-controller shape as every demo in this guide — parameters as raw strings, one use case, Results mapped onto HTTP:"""),
                        code("tc:controller", "java",
                                "guide/presentation/demo/ReliabilityDemoController.java — read, call, dial, probe, reset",
                                "server/src/main/java/guide/presentation/demo/ReliabilityDemoController.java", """
                                @GetMapping(value = "/api/demo/reliability/breaker", produces = MediaType.APPLICATION_JSON_VALUE)
                                ReadBreakerStatus.BreakerStatus breaker() {
                                    return readBreakerStatus.execute();
                                }

                                @PostMapping(value = "/api/demo/reliability/call", produces = MediaType.APPLICATION_JSON_VALUE)
                                ResilientCaller.CallReport call() {
                                    return callDownstream.execute();
                                }"""),
                        code("tc:trip-tour", "bash",
                                "trip the breaker, get rejected, probe, recover",
                                null, """
                                # start clean: CLOSED, healthy, virtual clock at t=0
                                curl -s -X POST localhost:8080/api/demo/reliability/reset | python3 -m json.tool | head -8

                                # one healthy call: SUCCESS on attempt 1, t=0->150ms
                                curl -s -X POST localhost:8080/api/demo/reliability/call | python3 -m json.tool | head -20

                                # now kill the downstream and trip the breaker (4 calls = the minimum window)
                                curl -s -X PATCH 'localhost:8080/api/demo/reliability/dial?mode=down'
                                for i in 1 2 3 4; do curl -s -X POST localhost:8080/api/demo/reliability/call > /dev/null; done
                                # the 4th report shows the trip: breakerStateBefore CLOSED, breakerStateAfter OPEN
                                curl -s localhost:8080/api/demo/reliability/breaker | python3 -m json.tool | grep -E 'state|openedAt|probeEligible|failureRate'
                                #   "state": "OPEN",  "openedAtMs": 12360,  "probeEligibleAtMs": 22360,
                                #   "failureRate": 100.0

                                # against the OPEN breaker the call is REJECTED AT THE DOOR — zero attempts
                                curl -s -X POST localhost:8080/api/demo/reliability/call | python3 -m json.tool | head -8
                                #   "outcome": "REJECTED_BY_BREAKER", "attempts": [], "totalElapsedMs": 0

                                # recover: force HALF_OPEN, heal the dial, let one probe close it
                                curl -s -X POST localhost:8080/api/demo/reliability/probe        # -> "HALF_OPEN"
                                curl -s -X PATCH 'localhost:8080/api/demo/reliability/dial?mode=healthy'
                                curl -s -X POST localhost:8080/api/demo/reliability/call | python3 -m json.tool | grep -E 'outcome|State'
                                #   "outcome": "SUCCESS" ... "breakerStateAfter": "CLOSED\""""),
                        code("tc:chaos-tour", "bash",
                                "timeouts, flakiness, and the chaos rail",
                                null, """
                                # the slow shape: every attempt times out at the 500ms budget,
                                # backoff 1s then 2s — the exact 4500ms timeline
                                curl -s -X PATCH 'localhost:8080/api/demo/reliability/dial?mode=slow'
                                curl -s -X POST localhost:8080/api/demo/reliability/call | python3 -m json.tool | grep -E 'attempt|startedAt|endedAt|outcome|Elapsed'
                                #   "outcome": "TIMED_OUT", "totalElapsedMs": 4500,
                                #   t=0->500, t=1500->2000, t=4000->4500

                                # the flaky shape: retries absorb the transient — SUCCESS at t=1320ms,
                                # and the breaker's window records a success, not the flake
                                curl -s -X POST localhost:8080/api/demo/reliability/reset > /dev/null
                                curl -s -X PATCH 'localhost:8080/api/demo/reliability/dial?mode=flaky'
                                curl -s -X POST localhost:8080/api/demo/reliability/call | python3 -m json.tool | grep -E 'outcome|totalElapsed'
                                curl -s localhost:8080/api/demo/reliability/breaker | python3 -m json.tool | grep -A3 metrics

                                # chaos, the app-wide rail: every attempt fails regardless of the dial
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' -d '{"injectFailure": true}'
                                curl -s localhost:8080/api/demo/reliability/breaker | python3 -m json.tool | grep -i rail
                                curl -s -X POST localhost:8080/api/demo/reliability/call | python3 -m json.tool | grep detail
                                #   "detail": "injected by the global failure rail (injectFailure=true)"

                                # put everything back the way you found it
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' -d '{"injectFailure": false}'
                                curl -s -X POST localhost:8080/api/demo/reliability/reset | python3 -m json.tool | grep state
                                #   "state": "CLOSED\""""),
                        text("tc:close", """
                                That last loop is the whole topic in one terminal session: a budget on every attempt, polite retries that absorbed the flake, a breaker that remembered four failures and refused to ask a fifth time, a probe that tested recovery with one call, a fallback story waiting behind every rejection — and a chaos rail you threw yourself, inside a blast radius of exactly one theater. The scaling topic asks the next question: this all held at one request at a time — what happens at ten thousand?""")));
    }
}
