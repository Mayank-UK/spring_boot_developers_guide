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
 * Topic 41 — Reactive Spring. Group platform, order 12. The topic defends
 * one structural position the whole guide has been building toward: THE
 * APP STAYS MVC ON VIRTUAL THREADS, and reactive is a TOOLBOX inside that
 * posture — Reactor pipelines for composability, Flux/Mono return types
 * bridged by MVC's async support, a reactive HTTP client whose waits ride
 * event loops, and backpressure as an actual demand protocol. The live
 * demo (reactive:compare) MEASURES the honest comparison — the same
 * workload on one-virtual-thread-per-item vs a flatMap-capped Reactor
 * pipeline (thread shapes, wall time, one shared checksum), plus a
 * deterministic drop-vs-buffer backpressure exhibit whose every count is
 * exact. Around that core: when reactive earns its complexity (and when
 * it does not), WebFlux controllers, WebClient (the Boot 4 minimal
 * starter), pipeline debugging and BlockHound, R2DBC in prose against
 * this app's real JDBC posture, and the closing MVC+virtual-threads vs
 * WebFlux decision framework — ending with the curl tour.
 */
public final class ReactiveSpringTopic {

    private ReactiveSpringTopic() {
    }

    public static Topic topic() {
        return new Topic("reactive-spring", "Reactive Spring",
                "Reactive as a toolbox inside an MVC-on-virtual-threads app — never a posture "
                        + "flip: Reactor's Flux/Mono (measured live against the one-thread-per-item "
                        + "blocking model it is always compared to), reactive return types served by "
                        + "servlet MVC's async bridge, the reactive WebClient, backpressure as a demand "
                        + "protocol (drop vs buffer, counted exactly), pipeline debugging and BlockHound, "
                        + "R2DBC in prose — and the honest decision framework at the end.",
                TopicGroupId.PLATFORM, 12,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-reactive", "Why reactive (and when not)",
                        "Two answers to the same problem — a request that waits. One parks a thread; one schedules a callback. Virtual threads made parking cheap, which changed the calculus, and this guide's position follows the numbers.",
                        text("wr:two-models", """
                                REACTIVE PROCESSING answers one question: what happens to a THREAD while a request waits for I/O? The servlet model this app runs parks the thread — one request per thread, blocked reads, blocked writes, simple stack traces, simple code. The reactive model SCHEDULES instead: a small number of event-loop threads carry a huge number of in-flight pipelines, and every wait is a callback registered against a future event — the thread that started the work is already serving something else when the answer arrives. That is the entire idea, and everything else in this topic — Flux, Mono, backpressure, WebClient, R2DBC — is machinery for living in the second world.

                                The honest history matters, because it explains the passion around the topic. In the THREAD-PER-REQUEST world, concurrency is capped by the thread pool: each OS thread costs ~1MB of stack and a scheduler slot, so 200 platform threads was a common ceiling — and 200 blocked threads meant 200 concurrent requests, period. The reactive stacks (Netty, WebFlux, Vert.x) were built to break exactly that ceiling: thousands of concurrent connections on a handful of event loops, with backpressure as the protocol that keeps a fast producer from drowning a slow consumer. Those were real wins, bought with real costs — every library in the path must be non-blocking (one JDBC call parks an event loop and stalls EVERYTHING on it), stack traces become operator chains, debugging gets harder, and the programming model is infectious: one blocking call inside a reactive pipeline is a production incident in waiting.

                                THEN VIRTUAL THREADS ARRIVED (JDK 21, and topic 38's posture in this very app: spring.threads.virtual.enabled). A parked VIRTUAL thread costs the JVM almost nothing — it unmounts from its carrier and the carrier moves on — so the classic ceiling dissolves: an MVC app on virtual threads holds tens of thousands of concurrent blocked requests with plain, blocking, debuggable code. That is why the calculus changed, and why this guide teaches reactive as a TOOLBOX rather than a religion: the blocking style with cheap threads is the right DEFAULT for the request/response + JDBC + Redis shape almost every business backend has; the reactive style earns its complexity where its specific superpowers matter — streaming responses element by element, one fan-out across many downstream calls, client-side concurrency against non-blocking clients, and backpressure as a first-class protocol instead of a bounded queue you built yourself.

                                One dependency fact grounds the whole topic in this repo: reactor was ALREADY here before this topic started. GraphQL's Spring integration is built on Reactor, so topic 13's starter quietly put reactor-core on the compile classpath (spring-boot-starter-graphql → spring-boot-reactor → reactor-core 3.8.7 — `mvn dependency:tree` is the proof, and the dependency-management topic taught you to ask):"""),
                        code("wr:pom-graphql", "xml",
                                "server/pom.xml — reactor's provenance in this app: topic 13's GraphQL starter pulled it in, versionless through the Boot BOM",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-graphql</artifactId>
                                </dependency>"""),
                        text("wr:position", """
                                So the position this topic takes, explicitly: THIS APP IS AND STAYS SERVLET MVC ON VIRTUAL THREADS — that is a blueprint decision (topic 38), and nothing in this topic flips it. What the topic adds is the reactive toolbox USED WELL inside that posture: Reactor as a library for pipelines and fan-out, Flux/Mono as CONTROLLER RETURN TYPES (servlet MVC bridges them beautifully — the next sections prove it), WebClient as the reactive HTTP client whose waits never spend a request thread, and backpressure as a vocabulary you will meet in every reactive system. Where the reactive-web-stack alternative (WebFlux end to end, R2DBC, no servlet container at all) genuinely wins, the section says so; where it costs you a week of debugging for a number you will never observe, that gets said too. The last section turns the whole comparison into a decision framework you can apply to your own services.""")),
                section("flux-mono", "Reactor's Flux and Mono",
                        "Flux is 0..N values over time; Mono is 0..1. Both are lazy — nothing runs until subscription — and the operators that assemble them are the vocabulary everything else in the topic reuses.",
                        text("fm:vocabulary", """
                                Reactor's two types cover the whole value space: FLUX<T> emits zero to N values and then completes (or errors), and MONO<T> emits at most one. A Mono is not a special case of Flux conceptually — it gets its own API because "at most one" is the shape of most real work: one row, one HTTP answer, one acknowledgement. Both types are LAZY: building the chain does nothing, SUBSCRIBING runs it, and nothing runs twice unless the source lets it (cold sources like Flux.range re-run per subscriber; hot ones like a sink or timer do not). That laziness is the first thing blocking-style developers trip over — a constructed-and-forgotten Mono is a bug that never fires — and it is also the superpower: subscription time is when concurrency hints, context, and demand all attach.

                                The operators are the language. MAP/FLATMAP transform (flatMap's inner publisher is where ASYNCHRONOUS fan-out lives — each inner Mono is one in-flight task, and its maxConcurrency argument caps how many run at once, which is demand shaping before this topic gives it a name). REDUCE/COLLECTLIST aggregate many values into one (a Flux becoming a Mono — the /sum endpoint below is exactly that). DELAYELEMENTS paces, ONBACKPRESSUREDROP/BUFFER answer overflow, SUBSCRIBEON says WHERE the work runs, PUBLISHON says where DOWNSTREAM runs. The engine this topic's demo runs picks the instructive subset — one deterministic work function, one blocking task, two ways to schedule it:
                                """),
                        code("fm:work", "java",
                                "guide/domain/reactive/CompareExecutionModels.java — the deterministic work unit: both execution models reduce over exactly this function, so their checksums agree by construction",
                                "server/src/main/java/guide/domain/reactive/CompareExecutionModels.java", """
                                public static long work(int item) {
                                    long value = item * 0x9E3779B97F4A7C15L;
                                    value ^= (value >>> 29);
                                    value *= 0xBF58476D1CE4E5B9L;
                                    value ^= (value >>> 32);
                                    return value;
                                }"""),
                        text("fm:pipeline-intro", """
                                And the reactive half of the comparison — the SAME blocking task (2ms simulated I/O, then work(item)), wrapped as a Mono per item, fanned out through flatMap with a concurrency cap, all of it on the boundedElastic scheduler (Reactor's designated home for blocking work; the parallel scheduler is for CPU-bound work, and pinning an event loop with blocking is the classic reactive sin):
                                """),
                        code("fm:pipeline", "java",
                                "guide/domain/reactive/CompareExecutionModels.java — the Reactor pipeline: flatMap's concurrency cap is the whole concurrency story, boundedElastic is where blocking work is allowed to block",
                                "server/src/main/java/guide/domain/reactive/CompareExecutionModels.java", """
                                Long sum = Flux.range(1, items)
                                        .flatMap(item -> Mono.fromCallable(() -> {
                                                    threads.add(Thread.currentThread().getName());
                                                    return blockingTask(item);
                                                })
                                                        .subscribeOn(Schedulers.boundedElastic()),
                                                concurrency)
                                        .reduce(0L, Long::sum)
                                        .block(Duration.ofSeconds(30));"""),
                        text("fm:block-at-edge", """
                                Read that chain bottom-up and you have the whole model: reduce aggregates the Flux into one Mono, and BLOCK() turns the pipeline into a plain long — this engine is called from a servlet request handler, so blocking AT THE EDGE (on a virtual request thread, with a timeout) is the correct bridge between the two worlds. The rule that keeps the bridge safe is worth pinning NOW because it recurs all topic: block() is legal at the edge (a request thread whose whole job is to wait for the assembled answer) and ILLEGAL inside pipelines (an operator's lambda may be running ON an event loop, where blocking stalls every other pipeline sharing that loop — the debugging section shows BlockHound catching exactly this). The client demo runs this engine beside its sibling — the same items, the same checksum, one virtual thread per item — and the comparison section interprets the numbers; the vocabulary here is what you will read in every Flux/Mono chain for the rest of the topic.""")),
                section("webflux-controllers", "Reactive controllers in an MVC world",
                        "You do not have to run WebFlux to return Flux and Mono. Servlet MVC bridges reactive return types through its async machinery — element-streaming for ndjson/SSE, deferred results for one value — and this app's demo controller is the compiled proof.",
                        text("wfc:bridge", """
                                THE FACT THAT SURPRISES EVERYONE: a @RestController on the SERVLET stack — this app, Tomcat, virtual threads — may return Flux and Mono directly. Spring MVC's ReactiveTypeHandler receives the reactive value, starts an ASYNC request (the servlet thread is released immediately, exactly like StreamingResponseBody and SseEmitter from topic 09), subscribes, and bridges elements to the response as they arrive. The media type decides the shape: for application/json a Flux is COLLECTED and written as one JSON array (the whole pipeline's answer, buffered); for STREAMING types — text/event-stream, application/x-ndjson — each element is serialized and flushed ON ARRIVAL, so the client sees rows tick in as the pipeline produces them; for a Mono, the single value is a deferred result the response waits for. That is the entire mechanism, and it means the reactive vocabulary from the last section can be used INSIDE the MVC app this guide is — no Netty, no posture change, no new error model (the @ControllerAdvice envelope still owns failures, because the async dispatch re-enters the same MVC exception machinery).

                                The demo controller for this topic carries both shapes as real, compiled, tested endpoints. The stream endpoint returns a Flux of items as ndjson — pace it with delayMs and watch the elements arrive one write at a time:
                                """),
                        code("wfc:controller-stream", "java",
                                "guide/presentation/demo/ReactiveDemoController.java — a WebFlux-style method served BY servlet MVC: ReactiveTypeHandler bridges the Flux to an async, element-per-write response",
                                "server/src/main/java/guide/presentation/demo/ReactiveDemoController.java", """
                                @GetMapping(value = "/api/demo/reactive/stream", produces = "application/x-ndjson")
                                Flux<ReactiveSelfFetch.StreamItem> stream(
                                        @RequestParam(name = "items", defaultValue = "5") int items,
                                        @RequestParam(name = "delayMs", defaultValue = "0") long delayMs) {
                                    requireInRange(items, 0, 256, "items");
                                    requireInRange((int) delayMs, 0, 1000, "delayMs");
                                    Flux<ReactiveSelfFetch.StreamItem> base = Flux.range(1, items)
                                            .map(seq -> new ReactiveSelfFetch.StreamItem(seq, "item-" + seq));
                                    return delayMs > 0 ? base.delayElements(Duration.ofMillis(delayMs)) : base;
                                }"""),
                        text("wfc:mono-intro", """
                                And the Mono shape — a Flux pipeline collapsed to one value, answered as a single JSON object once reduce completes:
                                """),
                        code("wfc:controller-sum", "java",
                                "guide/presentation/demo/ReactiveDemoController.java — Mono as a deferred result: MVC waits for the one value, then writes one JSON answer",
                                "server/src/main/java/guide/presentation/demo/ReactiveDemoController.java", """
                                @GetMapping(value = "/api/demo/reactive/sum", produces = MediaType.APPLICATION_JSON_VALUE)
                                Mono<Checksum> sum(@RequestParam(name = "items", defaultValue = "32") int items) {
                                    requireInRange(items, 1, CompareExecutionModels.MAX_ITEMS, "items");
                                    return Flux.range(1, items)
                                            .map(CompareExecutionModels::work)
                                            .reduce(0L, Long::sum)
                                            .map(total -> new Checksum(items, total));
                                }"""),
                        text("wfc:sse-contrast", """
                                The MVC-native alternative for the streaming shape is the one topic 28 already taught: SseEmitter, the servlet-native push primitive with named events, ids, replay and reconnect semantics. The reactive bridge does not replace it — for BROWSER push with resume semantics, SSE's protocol furniture (event ids, retry hints, Last-Event-ID) is the product, and the realtime topic's ticker is the specimen; the reactive stream endpoint above is the better fit for ELEMENT-AT-A-TIME DATA over plain ndjson, where curl is as good a consumer as a browser. The two share the same substrate (an async servlet request and a released handler thread), which is exactly why they can be quoted side by side:
                                """),
                        code("wfc:sse", "java",
                                "guide/presentation/realtime/SseTickerController.java — the servlet-native streaming primitive the reactive bridge rides beside: async request, terminal callbacks, one emitter per subscriber",
                                "server/src/main/java/guide/presentation/realtime/SseTickerController.java", """
                                SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
                                List<TickerFeed.Tick> missed = tickerFeed.replayAfter(resumeAfter);
                                StreamListener listener = new StreamListener(emitter, missed, limit);
                                activeEmitters.incrementAndGet();
                                // …
                                emitter.onCompletion(listener::detach);
                                emitter.onTimeout(listener::detach);
                                emitter.onError(error -> listener.detach());
                                return emitter;"""),
                        text("wfc:webflux-proper", """
                                And the thing this section deliberately does NOT do: boot the actual WebFlux stack. A true WebFlux app (spring-boot-starter-webflux, Netty, WebFluxAnnotationController instead of MVC's handlers) is a DIFFERENT server posture — the whole request pipeline is event-loop-native, filters and security and error handling all take the reactive form, and every blocking library in the path becomes a hazard. That posture is the subject of the last section's decision framework; what THIS section establishes is the middle path this app takes: reactive TYPES and reactive COMPOSITION on top of the servlet machinery, which buys the streaming shapes and the client-side superpowers without betting the service on the event loop. The one hard rule of the middle path is the edge rule from the last section — blocking only at the edge, never inside a chain — and the next section's client exists to show the other side of that rule: a reactive HTTP call where NOTHING blocks the request thread until the deliberate block at the very end.""")),
                section("webclient", "WebClient: the reactive HTTP client",
                        "RestClient's reactive sibling: the call is a pipeline, the wait rides the client's event loops, and the answer arrives as a Flux you can compose. Boot 4 ships a minimal starter for exactly this — no reactive server required.",
                        text("wcl:why", """
                                The client half of reactive earns its keep even when the server stays MVC, because OUTBOUND waits are where request threads actually die: ten sequential upstream calls at 100ms each park a servlet thread (even a virtual one — cheap, not free) for a full second, and the naive fix — parallel RestCalls on your own executor — rebuilds badly what WebClient gives you as an operator chain. WebClient is Spring's reactive HTTP client: you DESCRIBE the call (get + uri + accept + retrieve), get a PUBLISHER (bodyToFlux for a streamed body, bodyToMono for one), and the waiting happens on the CLIENT'S event loops (reactor-netty's reactor-http-nio threads) — your request thread participates only where you choose, at the operators you compose. Concurrency across N upstream calls is flatMap again — the same vocabulary as the last sections, which is the entire point: one mental model, client and server and pipeline.

                                The adapter this topic adds is the honest, architected version of that: a PORT in the domain (ReactiveSelfFetch — "fetch N items of the stream") with the WebClient machinery quarantined in the data layer, wired by the topic's config like every port-to-adapter pair in this guide. The fetch itself is one chain — ndjson elements decoded into records, thread evidence captured per element, everything collected and blocked ONCE at the edge with a timeout:
                                """),
                        code("wcl:chain", "java",
                                "guide/data/reactive/WebClientSelfFetch.java — the reactive fetch: describe the call, decode the ndjson stream, capture the threads, block exactly once at the edge",
                                "server/src/main/java/guide/data/reactive/WebClientSelfFetch.java", """
                                List<StreamItem> fetched = client.get()
                                        .uri(builder -> builder.path(STREAM_PATH)
                                                .queryParam("items", items)
                                                .queryParam("delayMs", delayMillis)
                                                .build())
                                        .accept(NDJSON)
                                        .retrieve()
                                        .bodyToFlux(StreamItem.class)
                                        .doOnNext(item -> threads.add(Thread.currentThread().getName()))
                                        .collectList()
                                        .block(Duration.ofSeconds(20));"""),
                        text("wcl:dependency", """
                                THE DEPENDENCY CHOICE (this is the dependency-management topic's discipline applied live): Boot 4's modularization split the old webflux starter's jobs apart, and there is now a MINIMAL artifact for exactly this use case — spring-boot-starter-webclient: the WebClient autoconfiguration (spring-boot-webclient) + reactor + reactor-netty's connector, with NO reactive server stack dragged in or activated (the reactive server autoconfiguration only engages a REACTIVE web application; this app's DispatcherServlet pins it servlet). It is versionless through the Boot 4.1.1 BOM, and it supersedes pulling spring-boot-starter-webflux "just for the client" — the way reactive apps did it for a decade:
                                """),
                        code("wcl:pom", "xml",
                                "server/pom.xml — the minimal WebClient starter: client autoconfiguration + connector, no reactive server (versionless, BOM-managed)",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-webclient</artifactId>
                                </dependency>"""),
                        text("wcl:fake-live", """
                                The wiring decision the adapter's config makes is this app's standing fake/live invariant, applied to a network client: the port gets TWO WebClients — a LIVE one (reactor-netty against this app's own listening port, for a genuine self-round-trip) and a STUB whose transport is an exchangeFunction that answers from the request URL itself — the same ndjson shape, zero sockets, fully deterministic, which is what the MockMvc tests and the default fake mode run. The ScenarioStore's per-area mode picks at REQUEST time:
                                """),
                        code("wcl:config", "java",
                                "guide/config/ReactiveDemoConfig.java — the port gets live and stubbed transports; the scenario mode decides at request time (fake default, no network to be teachable)",
                                "server/src/main/java/guide/config/ReactiveDemoConfig.java", """
                                WebClient liveClient = WebClient.builder()
                                        .baseUrl("http://localhost:" + port)
                                        .build();
                                WebClient stubClient = WebClient.builder()
                                        .exchangeFunction(WebClientSelfFetch::stubExchange)
                                        .build();
                                return new WebClientSelfFetch(liveClient, stubClient,
                                        () -> {
                                            ScenarioStore store = scenarioStore.getIfAvailable();
                                            return store != null && store.dataModeFor("reactive") == ScenarioDataMode.LIVE;
                                        });"""),
                        text("wcl:close", """
                                Two production notes close the section. RETRIES/TIMEOUTS: a reactive client has per-call timeouts (.timeout(duration) as an operator), retry policies (.retryWhen(Retry.backoff(...)) — the reliability topic's vocabulary, available here because the pipeline IS the computation), and connection-pool budgets of its own (the pool's max connections is to the client what HikariCP's maximum-pool-size is to the database — a number you set on purpose). And ERROR SEMANTICS: retrieve() maps 4xx/5xx to WebClientResponseException — an unchecked exception that surfaces at the block() edge or through the error channel of your chain; onStatus(...) lets you translate upstream failures into YOUR error taxonomy before they cross the port boundary, which is how the one-envelope rule survives a reactive client. What the adapter never does is swallow: a failed fetch errors the pipeline, the edge block rethrows, the controller's advice answers with the typed envelope.""")),
                section("backpressure", "Backpressure: demand as a protocol",
                        "A fast producer and a slow consumer need a contract. In the reactive world the contract is the Subscription itself — request(n) — and Reactor turns it into operators: drop, buffer, latest. The demo counts every option, deterministically.",
                        text("bp:protocol", """
                                Backpressure finally gets its mechanism. The scaling topic framed it as an architecture duty (bound everything, shed early, never queue stale work); reactive streams makes it a PROTOCOL: every subscription carries a demand signal — request(n) — and a well-behaved producer emits AT MOST what was requested. That is the whole reactive-streams contract (the org.reactivestreams.Subscription you will see below is literally it): no callbacks without demand, no unbounded hand-off, one tiny interface that lets a consumer say "I can take four more" and obligates the source to listen. Cold, pull-shaped sources (Flux.range, a database cursor) honor demand by construction — they simply do not run ahead. The interesting case is a HOT source (a ticker, a websocket, a user typing fast): it emits when the WORLD emits, demand or not — and that is where the overflow OPERATORS earn their keep: DROP discards what arrives without demand (lossy, newest-work-friendly — the scaling topic's shed verdict as a one-liner), BUFFER queues it (nothing lost, memory on the line), LATEST keeps only the most recent (a UI's shape).

                                The demo's engine stages the interesting case with NO clocks at all: a hot Sinks.Many source, a subscriber that demands a fixed initial amount and then STOPS (the slow consumer), and the two candidate policies. The subscriber is the protocol made visible — hookOnSubscribe is where initial demand is placed, hookOnNext is where the deliberate refusal to request more lives:
                                """),
                        code("bp:subscriber", "java",
                                "guide/domain/reactive/DemonstrateBackpressure.java — the demand protocol in a subscriber: request up front, then deliberately stop (a slow consumer, no timers needed)",
                                "server/src/main/java/guide/domain/reactive/DemonstrateBackpressure.java", """
                                BaseSubscriber<Integer> slowConsumer = new BaseSubscriber<>() {
                                    @Override
                                    protected void hookOnSubscribe(Subscription subscription) {
                                        request(initialDemand);
                                    }

                                    @Override
                                    protected void hookOnNext(Integer value) {
                                        delivered.add(value);
                                        // deliberately NO further request: the consumer is slow
                                    }
                                };"""),
                        text("bp:drop-intro", """
                                With DROP in the path, the overflow becomes a number: the operator absorbs the hot source's surplus and the count climbs by one per shed item — and the lesson lands when the consumer's LATE request finds nothing, because dropped values never return:
                                """),
                        code("bp:drop", "java",
                                "guide/domain/reactive/DemonstrateBackpressure.java — drop: shed the overflow, count the losses; a late request demonstrates the loss is permanent",
                                "server/src/main/java/guide/domain/reactive/DemonstrateBackpressure.java", """
                                source.asFlux()
                                        .onBackpressureDrop(value -> dropped.incrementAndGet())
                                        .subscribe(slowConsumer);
                                for (int item = 1; item <= items; item++) {
                                    source.tryEmitNext(item);
                                }
                                int afterInitial = delivered.size();
                                // the late catch-up: drop already shed the surplus — there is nothing left to give
                                slowConsumer.request(lateDemand);
                                int total = delivered.size();"""),
                        text("bp:buffer-intro", """
                                BUFFER answers the same overflow the other way — the sink's queue holds the surplus, and the SAME late demand drains it. Nothing lost, and the report shows the price line the scaling topic wrote: what is still queued after the catch-up, which under sustained overload is the memory that has to live somewhere:
                                """),
                        code("bp:buffer", "java",
                                "guide/domain/reactive/DemonstrateBackpressure.java — buffer: queue the overflow; the same late demand drains it (bound it in real use — onBackpressureBuffer(n))",
                                "server/src/main/java/guide/domain/reactive/DemonstrateBackpressure.java", """
                                source.asFlux().subscribe(slowConsumer);
                                // … the same hot emission loop as drop
                                int afterInitial = delivered.size();
                                int bufferedMid = items - afterInitial;
                                // the same late demand — and here it WORKS: the buffered surplus drains on request
                                slowConsumer.request(lateDemand);"""),
                        demo("bp:demo", "reactive:compare", "Measure both worlds, count the overflow",
                                "Run the SAME workload two ways and read the shapes: blocking on one virtual thread per item vs a Reactor pipeline whose flatMap concurrency you set — items, thread names, wall time, and the one checksum both models must produce. Then run the backpressure half: a hot source against a slow consumer, drop vs buffer, every count exact — including what the late demand recovers and what it never can."),
                        text("bp:verdict", """
                                What the run pins down, so you can check it against the panel. THE COMPARISON: both models report the same item count and the SAME checksum (identical work by construction); the blocking run shows one mvc-item-N thread per item — topic 38's posture, item-scaled; the reactive run shows a handful of boundedElastic threads regardless of item count (Reactor on JDK 21+ names them loomBoundedElastic-N — the same pool, loom-aware), with in-flight capped at your concurrency choice; wall times are measured, not promised — and the panel's reading lines own the uncomfortable truth that for pure blocking I/O at demo scale the virtual-thread model is routinely FASTER, because the reactive pipeline's win was never this benchmark. THE OVERFLOW: with 24 emitted and 4 demanded, drop delivers 4 and sheds 20 — the late request(4) recovers ZERO; buffer delivers 4 and queues 20 — the same late request drains 4 of them and the report still shows 16 waiting. Four numbers, two policies, the entire trade: LOSS vs MEMORY, chosen per stream, on purpose.

                                The production translation: DROP for telemetry, ticks, and anything where newest-beats-oldest (the realtime topic's ring buffer is this policy hand-rolled); BUFFER for work that must not be lost, but BOUNDED (onBackpressureBuffer(n) overflows into an ERROR you can route — an unbounded buffer under sustained overload is just a slower OutOfMemoryError, exactly the delayed failure the scaling topic warned about); and the demand protocol itself (limitRate, prefetch sizes on flatMap) for keeping a fast stage from outrunning a slow one inside one pipeline. The client's two buttons ride one GET each — no client-side math, the server's counts are the lesson:
                                """),
                        code("bp:client", "ts",
                                "client/src/ui/demos/reactive/compare_demo.tsx — the backpressure half as one URL: strategy and the deterministic parameters ride the query string",
                                "client/src/ui/demos/reactive/compare_demo.tsx", """
                                void safeRepositoryCall(async () =>
                                  parseBackpressure(
                                    await requestJson(
                                      config,
                                      `/demo/reactive/backpressure?strategy=${strategy}&items=24&initial=4&late=4`,
                                    ),
                                  ),
                                ).then((result) => {""")),
                section("debugging", "Debugging pipelines (and BlockHound)",
                        "Lazy chains hide control flow; assembly time is not run time. log() and checkpoint() turn the pipeline observable, StepVerifier pins it in tests — and BlockHound catches the one thing that silently ruins reactive systems: blocking on an event loop.",
                        text("dbg:observability", """
                                A reactive chain is a PROGRAM WHOSE CONTROL FLOW YOU CANNOT STEP THROUGH normally: assembly time (building the operator graph) is not subscription time (running it), the stack trace of a failure shows the operator chain rather than your if-statements, and work hops schedulers mid-pipeline so "where does this even run?" is a legitimate question. The toolbox, in the order you will reach for it. LOG() — insert `.log()` on any Publisher and every subscription, request, onNext, and terminal event prints with the operator's name: the single fastest way to see whether a chain is even subscribed (the forgotten-Mono bug from the Flux/Mono section shows up here as… nothing, which IS the diagnosis). CHECKPOINT("stage-name") — labels a spot so error traces carry YOUR vocabulary instead of a stack of reactor internals; it is this guide's request-id discipline applied to pipelines. DOONNEXT/DOONERROR — side-channel observation without touching the values, which is exactly how this topic's adapters capture the THREAD each element arrived on (the evidence the demo's report is built from):
                                """),
                        code("dbg:do-on-next", "java",
                                "guide/data/reactive/WebClientSelfFetch.java — observation as an operator: doOnNext records the thread every element arrived on, no timers, no debugger",
                                "server/src/main/java/guide/data/reactive/WebClientSelfFetch.java", """
                                .doOnNext(item -> threads.add(Thread.currentThread().getName()))"""),
                        text("dbg:tests", """
                                STEPVERIFIER is the test-side answer: it subscribes FOR you and asserts the SEQUENCE — expectNext(1, 2, 3).expectComplete(), or with virtual time (StepVerifier.withVirtualTime) a time-based chain like delayElements verifies in microseconds of wall clock — the same sleep-free determinism the reliability topic's breaker timelines used. This topic's engines test slightly differently (their counts are deterministic enough to assert through the plain HTTP layer, which doubles as an integration proof), but any pure pipeline you extract belongs in a StepVerifier. The one subtlety worth naming: assert VALUES and TERMINAL SIGNALS, never thread identities inside the chain — schedulers are implementation details that will move under you.

                                BLOCKHOUND is the guard with teeth: a javaagent that instruments blocking calls (Thread.sleep, socket reads, locks with contention) and FAILS them when they happen on a reactive thread (an event loop, a parallel scheduler). That failure is the topic's recurring sin made mechanical — one blocking JDBC call inside a flatMap stalls EVERY pipeline sharing that loop, and without BlockHound you find out from p99 graphs weeks later. It is deliberately NOT wired into this app (this is a blocking-first app; its request threads are ALLOWED to block — that is the posture), but the discipline it encodes applies to every pipeline you write, and a reactive-leaning codebase should run its tests with it:
                                """),
                        code("dbg:blockhound", "bash",
                                "BlockHound in a test JVM — the guard that makes 'no blocking on event loops' a test failure instead of a p99 regression",
                                null, """
                                # pom.xml (test scope): io.projectreactor.tools:blockhound
                                # (versionless where managed; otherwise the 1.0.x line on Central)
                                #
                                # one @Test that makes the rule executable:
                                #   BlockHound.install(b -> b.allowBlockingCallsInside("guide.core.", 5));
                                #   StepVerifier.create(pipelineUnderTest).expectComplete().verify();
                                #
                                # any Thread.sleep / JDBC read inside the chain on an event loop then fails as:
                                #   reactor.blockhound.BlockingOperationError:
                                #     Blocking call! java.lang.Thread.sleep
                                #     at ... (reactor-http-nio-2)
                                #
                                # run the suite with the agent when a codebase earns it (path = your
                                # local repo; the point is the -javaagent flag, not the exact layout):
                                mvn test -DargLine="-javaagent:/root/.m2/repository/io/projectreactor/tools/blockhound/1.0.13.RELEASE/blockhound-1.0.13.RELEASE.jar"
                                """),
                        text("dbg:close", """
                                The pragmatic ladder for this app's posture: pipelines are small and owned, so log()/checkpoint() at assembly boundaries plus counts asserted in tests carry most of the weight; BlockHound joins the moment a pipeline is allowed to touch I/O it did not wrap in boundedElastic. What NEVER changes is the edge rule — block() once, at the end, on a thread that is allowed to block — because every debugging tool above exists to protect exactly the threads that rule keeps safe.""")),
                section("r2dbc", "R2DBC: reactive relational access",
                        "The reactive answer to 'JDBC parks threads' — fully non-blocking drivers for PostgreSQL and friends. This app teaches it in prose against its real JDBC posture, because the honest comparison is rarely what the pitch implies.",
                        text("r2:idea", """
                                R2DBC (Reactive Relational Database Connectivity) is the relational-database layer built for the event-loop world: a fully NON-BLOCKING driver protocol (the PostgreSQL R2DBC driver multiplexes queries over a small number of connections, answering each as a Flux of rows), a ConnectionFactory instead of a DataSource, and Spring Data repositories that return Flux/Mono all the way down. The pitch writes itself: no parked threads per query, streams of rows consumed with backpressure, one uniform reactive vocabulary from HTTP handler to database cursor. The reality this guide is obligated to give you: on VIRTUAL THREADS, the parked-thread problem R2DBC was invented to solve is mostly GONE — a blocked virtual thread is cheap, so a plain JDBC call on a request thread (this app's entire data layer, topics 16–18) costs what it always cost in LATENCY and nothing in SCALABILITY. R2DBC's remaining edge is narrower and real: streaming HUGE row sets with live backpressure (an export that must not materialize ten million rows), and fully-non-blocking fan-out shapes (twenty concurrent queries composed as one pipeline) — and even those have a simpler blocking-world answer now (stream + virtual threads + a bounded executor).

                                The honest cost column is heavy enough that this app deliberately did NOT add an R2DBC driver: a SECOND relational stack means a second dialect of everything topics 16–18 built — transactions become chained Mono/Flux graphs instead of @Transactional's proxy (the AOP topic's mechanism does not compose with reactive return types the same way), batch/lazy-loading semantics differ, the ecosystem's tooling (Flyway interactions, observability instrumentation, team experience) is younger everywhere, and every blocked-thread guard from the debugging section now applies to YOUR data layer. The dependency-management topic's rule for such choices — verify what the Boot 4.1.1 BOM actually manages (it does manage the r2dbc BOM and a postgresql r2dbc driver, versionless) — says the integration is viable when you need it; this guide's judgment is that a teaching app whose data story is JDBC-first should SHOW the contrast rather than run both halves. The real config it stands against:
                                """),
                        code("r2:jdbc-posture", "yaml",
                                "server/src/main/resources/application.yml — the app's actual data posture: JDBC over HikariCP, pool as budget — the thing R2DBC would replace, shown as it really is",
                                "server/src/main/resources/application.yml", """
                                datasource:
                                  url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}
                                  username: ${SPRING_DATASOURCE_USERNAME:guide}
                                  password: ${SPRING_DATASOURCE_PASSWORD:guide}
                                  # … the pool comment block (max_connections arithmetic) then the budget:
                                  hikari:
                                    maximum-pool-size: 8"""),
                        text("r2:verdict", """
                                Read that block as the decision it is: 8 pooled CONNECTIONS gating a thread-per-request world — pool as budget, the scaling topic's connection arithmetic, waiting (cheaply) when the budget is spent. The R2DBC equivalent would gate on DEMAND instead: a small multiplexed connection set carrying many in-flight query streams, backpressured per subscriber — genuinely better for the streaming-row case, genuinely different for everything operational around it. If your service is R2DBC-shaped (a thin streaming API over big queries, no ORM features needed, team fluent in reactive), the recipe is the standard one: spring-boot-starter-data-r2dbc + the r2dbc-postgresql driver (both BOM-managed under 4.1.1), repositories returning Flux/Flux-adjacent types, transactions via DatabaseClient's transactional operator — and BlockHound in CI from day one, because a single blocking call in that world is not a style error but an outage. For everyone else — which is most services — the verdict of this guide's own architecture is already running in the yaml above: JDBC, a real pool, virtual threads to make the waiting free.""")),
                section("mvc-vs-webflux", "MVC + virtual threads vs WebFlux: the decision",
                        "The comparison this topic kept promising: same workload, both models, measured — then generalized into the framework decision. The demo's numbers are the entry point; the decision framework is the takeaway.",
                        text("mv:measured", """
                                First the measured half, because opinions about concurrency models should start with data. The demo runs THE SAME items through both models: the blocking model spawns one virtual thread per item — literally the posture line this app's configuration pins for every request it serves — and the reactive model runs the flatMap-capped pipeline from the Flux/Mono section. The blocking half, as code:
                                """),
                        code("mv:blocking", "java",
                                "guide/domain/reactive/CompareExecutionModels.java — the MVC-shaped half: one named virtual thread per item, blocking honestly, futures joined at the edge",
                                "server/src/main/java/guide/domain/reactive/CompareExecutionModels.java", """
                                try (ExecutorService perItemThreads = Executors.newThreadPerTaskExecutor(
                                        Thread.ofVirtual().name("mvc-item-", 1).factory())) {
                                    // … one task per item: park 2ms in the simulated I/O, compute work(item)
                                    sum = 0;
                                    for (Future<Long> future : futures) {
                                        sum += future.get();
                                    }
                                }"""),
                        text("mv:posture", """
                                And the posture that makes this the app's default, verbatim from the real configuration file (topic 38's one-line flip, twelve-factor overridable like everything else in that file):
                                """),
                        code("mv:yml", "yaml",
                                "server/src/main/resources/application.yml — the blueprint decision this topic never flips: servlet MVC, one virtual thread per request",
                                "server/src/main/resources/application.yml", """
                                # VIRTUAL. One line flips Boot's MVC stack onto one virtual thread per
                                # request: Tomcat mounts handlers on virtual threads instead of drawing
                                # from a fixed platform pool, so a handler parked on a database call, a
                                # redis round trip, or an upstream HTTP hop holds no OS thread while it
                                # waits. That is the right default for the blocking-I/O shape this guide
                                # teaches — the whole trade-off (and the pinning caveats) is topic 38's
                                # virtual-threads-at-scale section.
                                threads:
                                  virtual:
                                    enabled: true"""),
                        text("mv:framework", """
                                Now the framework decision, stated once and completely. CHOOSE WEBFLUX END-TO-END when your service is shaped like its wins: a STREAMING service (long-lived connections, element-by-element push, backpressure as product — the realtime topic's shapes at fleet scale), a GATEWAY whose entire job is fan-out and merge across non-blocking upstreams (the WebClient section's composition, but for every request), a world of NON-BLOCKING dependencies you cannot replace (R2DBC-shaped data, reactive messaging clients), or a concurrency ceiling that virtual threads do not lift for you (rare now: pinned-carrier situations, savage memory budgets per connection). CHOOSE MVC + VIRTUAL THREADS when your service looks like most services: request/response over JDBC/Redis/REST, teams that debug with stack traces, an ecosystem whose best tools are blocking — which is precisely this guide's shape, hence its posture. The HYBRID this topic demonstrated is legitimate and common: MVC posture, reactive toolbox where it pays (streaming endpoints, WebClient fan-out, pipelines with demand control) — the middle path costs you the edge rule (block only at the edge) and buys both worlds' best parts.

                                THE MEASURED VERDICT the demo will keep showing you: for blocking I/O at realistic scale, virtual threads match or beat the pipeline (they parallelize trivially; the pipeline's flatMap cap is an artificial ceiling you set), with code that reads like the domain. The pipeline wins on RESOURCE BOUNDS (threads are bounded by construction, not by discipline), on DEMAND (the protocol is built in, not bolted on), and on COMPOSITION (retry, timeout, rate-limit as operators — the reliability topic's machinery as one-liners). Those wins are architectural, not benchmark-shaped — which is exactly why the decision is a SHAPE question, not a speed question, and why this guide runs the blocking shape with the reactive toolbox bolted on where the shape pays.""")),
                section("terminal-companion", "Terminal companion",
                        "The topic curled: drive the comparison and the overflow exhibit from curl until the thread shapes and the drop-vs-buffer counts are numbers you saw, then flip the fetch live and watch a real reactive round trip.",
                        text("tc:intro", """
                                All of it is on the demo controller — the same thin shape as every demo in this guide (parameters validated at the edge, one engine call, typed 400 envelopes for bad input):
                                """),
                        code("tc:controller", "java",
                                "guide/presentation/demo/ReactiveDemoController.java — the comparison endpoint: bounds enforced, one engine call, the measured report served as JSON",
                                "server/src/main/java/guide/presentation/demo/ReactiveDemoController.java", """
                                @GetMapping(value = "/api/demo/reactive/compare", produces = MediaType.APPLICATION_JSON_VALUE)
                                CompareExecutionModels.CompareReport compare(
                                        @RequestParam(name = "items", defaultValue = "32") int items,
                                        @RequestParam(name = "concurrency", defaultValue = "8") int concurrency) {
                                    requireInRange(items, 1, CompareExecutionModels.MAX_ITEMS, "items");
                                    requireInRange(concurrency, 1, CompareExecutionModels.MAX_CONCURRENCY, "concurrency");
                                    return compareExecutionModels.compare(items, concurrency);
                                }"""),
                        text("tc:tour", """
                                The tour — comparison first (read the distinctThreads and sampleThreads fields side by side; the checksums must agree), then the overflow, then the stream, then the client half flipped live:
                                """),
                        code("tc:curl", "bash",
                                "the reactive tour, end to end",
                                null, """
                                # the comparison: same 48 items, both models, one shared checksum —
                                # blocking runs ~48 mvc-item-* threads, the pipeline a few boundedElastic-*
                                curl -s 'localhost:8080/api/demo/reactive/compare?items=48&concurrency=8' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); \\
                                  [print(f"{m[\"model\"]}: {m[\"distinctThreads\"]} threads {m[\"sampleThreads\"][:2]}") \\
                                   for m in (r["blockingPerItem"], r["reactorPipeline"])]; \\
                                  print("checksum both:", r["workChecksum"])'
                                # blocking-virtual-threads: 48 threads ['mvc-item-1', 'mvc-item-10']
                                # reactor-pipeline: ~8 threads ['loomBoundedElastic-1', 'loomBoundedElastic-2']
                                # checksum both: -6415601794888525841

                                # the overflow: drop sheds 20 of 24, the late request recovers nothing;
                                # buffer queues them, the same late demand drains 4
                                curl -s 'localhost:8080/api/demo/reactive/backpressure?strategy=drop&items=24&initial=4&late=4' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); \\
                                  print(f"delivered {r[\"deliveredTotal\"]}/24, dropped {r[\"dropped\"]}")'
                                # delivered 4/24, dropped 20
                                curl -s 'localhost:8080/api/demo/reactive/backpressure?strategy=buffer&items=24&initial=4&late=4' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); \\
                                  print(f"delivered {r[\"deliveredTotal\"]}/24, still buffered {r[\"stillBuffered\"]}")'
                                # delivered 8/24, still buffered 16

                                # reactive return types through MVC: ndjson, one line per element
                                curl -s 'localhost:8080/api/demo/reactive/stream?items=3&delayMs=200'
                                # {"seq":1,"payload":"item-1"}
                                # {"seq":2,"payload":"item-2"}
                                # {"seq":3,"payload":"item-3"}

                                # a Mono over a Flux pipeline — one JSON answer when reduce completes
                                curl -s 'localhost:8080/api/demo/reactive/sum?items=48'

                                # the WebClient half — stubbed by default, then a REAL reactor-netty round trip:
                                curl -s 'localhost:8080/api/demo/reactive/fetch?items=3' | python3 -m json.tool | head -6
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' -d '{"dataMode":"live"}'
                                curl -s 'localhost:8080/api/demo/reactive/fetch?items=3&delayMs=50' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); \\
                                  print(r["mode"], r["threads"])'
                                # live ['reactor-http-nio-1', 'reactor-http-nio-2'] — event loops, no servlet thread"""),
                        text("tc:close", """
                                That last line is the topic's whole position in one field: a servlet-MVC app, on virtual threads, whose outbound HTTP wait rode a reactor event loop — both models in one process, each doing what it is best at. The platform arc is complete: performance made one instance fast, reliability made it survive failure, scaling made it a fleet, and this topic gave you the second concurrency model — not as a migration path, but as a tool with a shape you can now recognize, measure, and either adopt or decline with reasons. The capstone topics next (best practices, third-party integrations, advanced architecture) assemble everything so far into the habits, the integration patterns, and the architecture vocabulary that hold a system like this together as it grows.""")));
    }
}
