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
 * Topic 38 — Performance. Group platform, order 9. The discipline the whole
 * topic defends: MEASURE FIRST, then move the cheapest lever that the
 * measurement names — never a lever because it is famous. JFR before
 * opinions, thread hygiene before thread counts, a LIVE compression
 * benchmark (perf:bench: gzip vs identity over a real-shaped few-KB JSON
 * payload, medians after warm-up, ratio deterministic and timing never
 * asserted to an exact value), virtual threads at scale (the switch this
 * app itself runs on), the HikariCP pool as a budget, G1/ZGC as a pause
 * decision rather than an incantation, and a closing checklist that puts
 * every lever in order.
 */
public final class PerformanceTopic {

    private PerformanceTopic() {
    }

    public static Topic topic() {
        return new Topic("performance", "Performance",
                "Measure first, then move the cheapest lever the measurement names: JFR "
                        + "before opinions, thread hygiene before thread counts, a live gzip-vs-identity "
                        + "benchmark, virtual threads at scale (the switch this app runs on), HikariCP "
                        + "as a budget, G1/ZGC as a pause decision, and the checklist that orders every "
                        + "lever.",
                TopicGroupId.PLATFORM, 9,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("measure-first-jfr", "Measure first: JFR before opinions",
                        "The performance conversation starts with a number, not a suspicion. JFR records what the process actually did, at production-safe overhead — everything after it is reading.",
                        text("mf:why", """
                                Every performance story that ends well starts the same way: a MEASUREMENT names the cost, a CHANGE addresses it, and the SAME measurement proves the fix. Every story that ends badly skipped the first step and the third — someone read a blog post, applied a famous lever (a bigger pool, a different collector, a cache), and never learned what the lever actually moved. This topic's first rule is therefore procedural, not technical: NO CHANGE WITHOUT A NUMBER BEFORE IT, and no claim of victory without the same number after. The observability topic gave you the metrics layer (the request rates, the p95s, the error budget); performance work starts one level deeper, at the QUESTION LAYER: given that p95 is 900ms, WHERE do those milliseconds live? In the database? In lock waits? In GC? In a thread pool that is too small? That question is answered by a PROFILER, and on the JDK the profiler that earns the first slot is Java Flight Recorder — JFR.
                                
                                JFR is the right first tool for three honest reasons. ONE: it is BUILT IN — no agent, no dependency, no license; `jcmd <pid> JFR.start` is the whole installation (the terminal companion runs the tour). TWO: it is CHEAP BY DESIGN — the default event set costs well under one percent of overhead, which is why production systems can (and do) run it continuously, so the recording you need after an incident is already on disk instead of being an unreproducible memory. THREE: it answers the where question with SAMPLES instead of instrumentation — method execution samples, allocation samples, lock contention events, GC pauses, safepoint waits — stitched together with timestamps so you can correlate the slow second with what the process was doing during it. The debugging topic used JFR's siblings (thread dumps, heap dumps) for failure diagnosis; performance work uses JFR for the same reason a doctor uses blood work before surgery: to cut in the right place.
                                
                                Microbenchmarks are the other half of measure-first, and they deserve their honesty rules stated up front, because they are where good instincts go to die. A benchmark that times the FIRST pass measures the JIT compiler, not the code — warm up first. A benchmark that reports the MEAN of noisy samples flatters lucky outliers — report medians. A benchmark that asserts an exact microsecond on a shared machine will fail on the CI runner and pass on your laptop and mean neither — assert only what is DETERMINISTIC (bytes, counts, ratios), report what is not (time). This topic's live benchmark was built under exactly those rules, and its timing loop is worth quoting before the demo runs it:"""),
                        code("mf:timing-loop", "java",
                                "guide/domain/performance/CompressionBenchmark.java — warm-up untimed, then every pass timed separately",
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
                        text("mf:medians", """
                                Read the loop the way a reviewer should: the warm-up loop is UNtimed (it exists only so the JIT compiles the hot path before the clock starts), every timed pass measures COMPRESS and ROUND-TRIP separately (they are different costs with different owners — the server pays compress, the client pays decompress), and each pass samples System.nanoTime() twice around each phase rather than once around everything. The samples then reduce to one number per phase:
                                """),
                        code("mf:median", "java",
                                "guide/domain/performance/CompressionBenchmark.java — the median: even counts average the two middle samples",
                                "server/src/main/java/guide/domain/performance/CompressionBenchmark.java", """
                                private static long medianMicros(long[] nanos) {
                                    long[] sorted = nanos.clone();
                                    Arrays.sort(sorted);
                                    long median = sorted.length % 2 == 1
                                            ? sorted[sorted.length / 2]
                                            : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
                                    return Math.round(median / 1_000.0);
                                }"""),
                        text("mf:verdict", """
                                The median (not the mean — a GC pause or a scheduler hiccup lands in one sample and would drag a mean toward fiction) is converted to microseconds and reported. The tests around this demo pin the DETERMINISTIC facts: the compressed byte count is an assertion; the micros are reported and never asserted. That split is the whole measure-first ethic in miniature, and it is why the demo can run in the guide at all — it tells the truth on every machine, just different truths about time.
                                
                                One more honesty rule closes the section, because it is the one teams skip: MICRO and MACRO must agree before you believe either. A microbenchmark that shows a 3x win on a loop, applied to a request that spends 2% of its time in that loop, moves the p95 by nothing — the MACRO measurement (JFR, the metrics layer) is the referee of whether the micro win mattered. Work the ladder in order: metrics say WHAT is slow, JFR says WHERE, a benchmark says HOW MUCH a specific change buys, and the metrics again say whether it mattered. The rest of this topic is a tour of the levers you will find at the WHERE stage — threads, bytes, pools, collectors — each with its measurement story attached.""")),
                section("thread-hygiene", "Thread hygiene: pools, leaks, and discipline",
                        "Before reaching for more threads, make the ones you have behave: sized deliberately, bounded honestly, and shut down on every path. The guide's own exhibit shows the discipline.",
                        text("th:cost", """
                                Threads are not free, and performance work that forgets this becomes an arms race with the OS. Every PLATFORM thread pins a 1:1-mapped OS thread (kernel object, scheduler entry) and, by default, roughly a MEGABYTE of reserved stack — a thousand idle platform threads is a gigabyte of address space and a real scheduling tax before any of them does work. Thread hygiene is the set of habits that keeps that cost deliberate: SIZE pools for the work (a pool is a rationing device, not a bucket you fill because more sounds faster), BOUND everything that can grow (queues, batches, concurrent operations — an unbounded queue is a delayed OutOfMemoryError wearing a productivity costume), and SHUT DOWN everything you start, on the success path AND the failure path. A leaked executor does not fail today; it fails in week three, at 3 a.m., when its threads have quietly consumed the process — the debugging topic's stall theater exists to catch exactly such ghosts.
                                
                                Pool sizing has exactly one honest starting point: the WAIT PROFILE of the work. CPU-bound work wants roughly CORES threads (more just adds context-switching). Blocking work wants enough concurrency to keep the device busy while others wait — arrival rate times average wait time, Little's law wearing overalls — and that number is usually much larger than cores, which is precisely the tension virtual threads (next sections) resolve. But note what good hygiene says even for blocking pools: the pool size is protecting a DOWNSTREAM RESOURCE (a database, an external API). If the resource is the constraint, the modern answer is not a bigger pool but a bounded permit — a Semaphore around the call — because a pool you sized to the resource and a semaphore you sized to the resource differ mainly in who pays for the idle threads.
                                
                                The JVM topic's threads exhibit was built under these rules, and its two private helpers are the portable habit — failures inside tasks SURFACE (a Future whose exception is never .get()ed is a swallowed exception), and teardown happens on every path:"""),
                        code("th:await-and-shutdown", "java",
                                "guide/domain/jvm/ThreadsExhibit.java — surface every failure, shut the pool down, interrupt back",
                                "server/src/main/java/guide/domain/jvm/ThreadsExhibit.java", """
                                private static void awaitAll(List<Future<?>> futures) {
                                    try {
                                        for (Future<?> future : futures) {
                                            future.get();
                                        }
                                    } catch (InterruptedException error) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException("interrupted while awaiting demo tasks", error);
                                    } catch (ExecutionException error) {
                                        throw new IllegalStateException("demo task failed", error.getCause());
                                    }
                                }

                                // … the pool teardown lives right beside it, on every path

                                private static void shutdown(ExecutorService pool) {
                                    pool.shutdown();
                                    try {
                                        if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                                            pool.shutdownNow();
                                        }
                                    } catch (InterruptedException error) {
                                        pool.shutdownNow();
                                        Thread.currentThread().interrupt();
                                    }
                                }"""),
                        text("th:verdict", """
                                Three details in those twenty lines carry the section. awaitAll calls future.get() for EVERY task — the ExecutionException branch is where a task's real failure surfaces, unwrapped and rethrown with its cause; the team that skips this writes an executor whose tasks fail silently forever. shutdown() is the polite two-step: shutdown() (stop accepting, let queued work finish), awaitTermination with a BUDGET, then shutdownNow() when the budget expires — never shutdownNow() first (it discards queued work) and never shutdown() alone (a hung task pins the process open; the deployment topic's graceful-shutdown budget is the same discipline at process scale). And both InterruptedException handlers RESTORE the interrupt flag — swallowing it would lie to whatever is shutting YOU down. Hygiene, not heroics: every pool an owner, every queue a bound, every thread a shutdown path. With that floor laid, the interesting question becomes how many threads the request model itself needs — which is the compression benchmark's neighbor and the next-but-one section's whole subject.""")),
                section("compression-benchmark", "Compression: the CPU-for-bytes trade, measured",
                        "gzip buys bandwidth with CPU, and the trade has a dial (the level) and a floor (tiny responses lose money). The perf:bench demo measures the whole thing live against a real-shaped JSON payload.",
                        text("cb:principle", """
                                Response compression is the cheapest performance lever in the book — when it applies — because it moves the constraint that is usually the scarcest: bytes on the wire. The mechanics are a NEGOTIATION, not a decision the server makes alone: the client offers `Accept-Encoding: gzip` (every browser, every HTTP client library worth using), and the server answers either the compressed body with `Content-Encoding: gzip` or the identity body — per response, per mime type, per size. Underneath, gzip is DEFLATE: a sliding-window match finder (LZ77 — "these 40 bytes appeared 200 bytes ago") followed by Huffman coding of what is left. That explains both halves of the trade you are about to measure: JSON with repeated keys and prose compresses DRAMATICALLY (the benchmark's real-shaped payload lands far under half its identity size), and the compression COST is CPU — mostly the match search, whose depth is the LEVEL dial (1 fastest, 9 most obsessive, 6 the default's family).
                                
                                Spring Boot puts the whole decision in three properties, and this app runs with them ON — the one clearly-flagged teaching block this topic adds to the yaml, twelve-factor overrides included:"""),
                        code("cb:yml", "yaml",
                                "server/src/main/resources/application.yml — connector compression: enabled by default, env-overridable, with a size floor",
                                "server/src/main/resources/application.yml", """
                                compression:
                                  enabled: ${SERVER_COMPRESSION_ENABLED:true}
                                  mime-types: ${SERVER_COMPRESSION_MIME_TYPES:application/json,text/html,text/css,text/plain}
                                  min-response-size: ${SERVER_COMPRESSION_MIN_RESPONSE_SIZE:1KB}"""),
                        text("cb:config-reading", """
                                Read the block like a budget. `enabled` with an env override is the usual twelve-factor shape (a proxy that already compresses at the edge would set SERVER_COMPRESSION_ENABLED=false rather than pay twice). `mime-types` is a comma-separated string that relaxed binding still delivers to Boot as a list — deliberate, because the benchmark demo reads the SAME line through the Environment so it can report the EFFECTIVE posture instead of a claim baked into prose. `min-response-size: 1KB` is the floor that keeps tiny answers identity: a 200-byte body spends more CPU compressing than the saved bytes are worth on any network faster than a wet string, and gzip's ~20 bytes of header plus trailer eat the margin from below. And note what is deliberately ABSENT: text/event-stream. Server-sent events answer INCREMENTALLY — the realtime topic's ticker sends a beat every second — and a compressor must buffer to find matches, which would turn a live stream into a delayed one. Streaming and compression can be reconciled (flush per event, compress per chunk) but never by accident; the mime list is the guardrail.
                                
                                The benchmark engine itself is a single class with no dependency but the JDK — `java.util.zip`, the same deflate family the connector calls — so what it measures is what the wire would actually pay:"""),
                        code("cb:engine", "java",
                                "guide/domain/performance/CompressionBenchmark.java — the report shape, and every level run over the same payload",
                                "server/src/main/java/guide/domain/performance/CompressionBenchmark.java", """
                                public record LevelResult(int level, int rawBytes, int compressedBytes, double compressedToRawRatio,
                                        long medianCompressMicros, long medianRoundTripMicros) {
                                }

                                // …

                                public RawBench benchmark(String profile, int passes) {
                                    BuiltPayload built = buildPayload(profile);
                                    byte[] raw = built.json().getBytes(StandardCharsets.UTF_8);
                                    List<LevelResult> results = new ArrayList<>();
                                    for (int level : LEVELS) {
                                        results.add(measureLevel(raw, level, passes));
                                    }
                                    return new RawBench(profile, raw.length, built.items(), results);
                                }"""),
                        text("cb:use-case", """
                                The levels compared are fixed on purpose — 1 and 6, fastest versus default — because the teaching point is the SHAPE of the trade (a few more percent of ratio for measurably more CPU), not an exhaustive sweep the reader cannot act on. The payload is built to compress like reality (repeated JSON keys, rotating prose bodies, varied numbers — NOT "aaaa", whose 1% ratio would teach magic instead of mechanics). The use case around the engine owns the parameter policy, exactly like every other demo in the guide — bad input is a typed 400, never a 500:"""),
                        code("cb:param-policy", "java",
                                "guide/domain/performance/RunCompressionBenchmark.java — the validation seam the controller trusts",
                                "server/src/main/java/guide/domain/performance/RunCompressionBenchmark.java", """
                                Integer passCount = parsePasses(passes);
                                if (passCount == null) {
                                    return Result.failed(Failure.of(FailureKind.VALIDATION,
                                            "passes must be an integer between 1 and " + CompressionBenchmark.MAX_PASSES,
                                            Map.of("parameter", "passes", "min", 1, "max", CompressionBenchmark.MAX_PASSES)));
                                }"""),
                        text("cb:effective-posture", """
                                And the report's last discipline is the one worth stealing for every operational surface you ever build: it does not CLAIM the connector's posture, it READS it. The composition root pulls the three live property values out of the Environment at startup and hands them to the domain as a plain value, so the reading line is generated from what the process would actually do:"""),
                        code("cb:effective", "java",
                                "guide/domain/performance/RunCompressionBenchmark.java — the reading line is built FROM the settings, so it cannot drift from them",
                                "server/src/main/java/guide/domain/performance/RunCompressionBenchmark.java", """
                                lines.add(serverCompression.enabled()
                                        ? "this process's connector compression is ON: " + serverCompression.mimeTypes() + " over "
                                                + serverCompression.minResponseSize() + " ride gzip when the client sends Accept-Encoding."
                                        : "this process's connector compression is OFF: every response leaves at identity regardless of level.");"""),
                        code("cb:config-bean", "java",
                                "guide/config/PerfDemoConfig.java — the three property spellings read once, at the composition root",
                                "server/src/main/java/guide/config/PerfDemoConfig.java", """
                                @Bean
                                ServerCompression serverCompression(Environment environment) {
                                    boolean enabled = Boolean.parseBoolean(environment.getProperty(ENABLED_KEY, "false"));
                                    String mimeTypes = environment.getProperty(MIME_TYPES_KEY, "");
                                    String minResponseSize = environment.getProperty(MIN_SIZE_KEY, "2KB");
                                    return new ServerCompression(enabled,
                                            Arrays.stream(mimeTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                                            minResponseSize);
                                }"""),
                        demo("cb:bench-demo", "perf:bench", "Run the compression benchmark",
                                "Pick a payload profile (small, medium, large — a real-shaped JSON feed) and a pass count, then run: the report answers with the identity size, gzip's bytes and ratio at levels 1 and 6, median compress and round-trip micros after warm-up, and the process's effective server.compression posture. Re-run at different sizes and watch the ratio hold while the micros wander — the deterministic part and the honest part, side by side."),
                        text("cb:client-half", """
                                The client half of the demo is deliberately boring — two query parameters, one GET, the report renders itself — because the interesting code is on the server, where the measurement lives:
                                """),
                        code("cb:client", "ts",
                                "client/src/ui/demos/performance/perf_bench_demo.tsx — run: two params, one GET, no client-side math",
                                "client/src/ui/demos/performance/perf_bench_demo.tsx", """
                                const runBenchmark = useCallback(() => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () =>
                                    parseReport(await requestJson(config, `/demo/perf/bench?payload=${payload}&passes=${passes}`)),
                                  ).then((result) => {
                                    if (result.ok) {
                                      setReport(result.value);
                                      setPanel({ kind: 'idle' });
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                }, [config, payload, passes]);"""),
                        text("cb:verdict", """
                                What the numbers will show, so you can check the demo against expectations: the RATIO barely moves between level 1 and level 6 (a few percent — the last ratio points cost the most CPU), the ratio IMPROVES as the payload grows (a bigger window gives the matcher more to find — run small then large and compare), and the micros are genuinely noisy run to run, which is why the tests assert bytes and never milliseconds. When you are done, hold one planning rule: compression is a BANDWIDTH lever. If the measurement says the request is slow because of server CPU or database time, gzip does nothing for the caller's latency except add a little — measure first, then pick the lever. The terminal companion closes the topic with the wire proof: the same endpoint fetched with identity and gzip Accept-Encoding headers, byte counts side by side from the real connector.""")),
                section("virtual-threads-at-scale", "Virtual threads at scale",
                        "The blocking-I/O answer at scale: one thread per task, parked — not blocked — while it waits. The app you are reading runs on this switch.",
                        text("vt:recap", """
                                The JVM topic ran the small experiment: 24 blocking tasks on a pool of 4 platform threads pay 6 waves of waiting; the same 24 tasks on 24 virtual threads all run at once, each PARKED while it waits. This section is the same lesson at the scale where it changes architecture. The platform-thread ceiling is arithmetic, not tuning: 10,000 concurrent requests that each wait 50ms on a database need 10,000 threads to overlap their waits, and 10,000 platform threads is 10GB of stack and a scheduler the OS will not thank you for. Historically the escape was the EVENT LOOP — the reactive stack the reactive-spring topic covers, where a handful of threads juggle thousands of in-flight operations and YOUR code pays the complexity tax (no blocking, ever, anywhere on the hot path). Virtual threads are the runtime doing that juggling FOR you: the JVM mounts each virtual thread on a carrier platform thread only while it runs Java code, and when the code blocks on something the runtime understands — a socket read, a lock, a sleep, `java.net.http` calls, JDBC drivers that use the right primitives — the virtual thread unmounts and the carrier moves on. You write straightforward blocking code; the runtime delivers event-loop-class concurrency.
                                
                                In Spring Boot, the switch for a servlet-stack app is ONE line — and this guide's server genuinely runs on it (the flagged teaching block this topic added to the yaml):"""),
                        code("vt:yml", "yaml",
                                "server/src/main/resources/application.yml — the whole switch: MVC handlers mount on virtual threads",
                                "server/src/main/resources/application.yml", """
                                threads:
                                  virtual:
                                    enabled: true"""),
                        text("vt:what-it-changes", """
                                What that line actually does is worth precision, because the mental model is the difference between confidence and cargo cult. With it on, Boot's web tier hands each REQUEST to a fresh virtual thread instead of drawing from Tomcat's fixed platform pool — so a request parked on a slow database call, a redis round trip, or an upstream HTTP hop holds no OS thread while it waits, and the platform threads Tomcat does keep stay busy running code instead of sleeping on behalf of others. The exhibit you met in topic 02 is the mechanism in miniature — and its virtual run is now a description of what serves every page of this guide:
                                """),
                        code("vt:run-virtual", "java",
                                "guide/domain/jvm/ThreadsExhibit.java — one virtual thread per task; the executor closes through try-with-resources",
                                "server/src/main/java/guide/domain/jvm/ThreadsExhibit.java", """
                                public RunReport runVirtual(int tasks) {
                                    long start = System.nanoTime();
                                    List<String> names = Collections.synchronizedList(new ArrayList<>(tasks));
                                    try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
                                        List<Future<?>> futures = new ArrayList<>();
                                        for (int index = 0; index < tasks; index++) {
                                            futures.add(virtual.submit(() -> names.add(blockAndReport())));
                                        }
                                        awaitAll(futures);
                                    }
                                    return report("virtual", tasks, start, names);
                                }"""),
                        text("vt:honesty", """
                                Now the honesty list, because virtual threads are a lever with edges. They WIN when work is blocking-I/O-shaped — which describes the majority of business services (this guide's own shape: MVC handlers doing JDBC, redis, mongo, upstream HTTP). They WIN NOTHING for CPU-bound work: 10,000 virtual threads doing math still need N cores times one carrier each, and the unmounting machinery is pure overhead; compute belongs in a SIZED platform pool (thread hygiene, last section) or in structured parallel streams. The classic caveat is PINNING: a virtual thread that blocks inside a `synchronized` block or an old native frame cannot unmount, pinning its carrier — much improved on recent JDKs (JDK 24 removed the synchronized case; this app runs 25), but the surviving cases (native calls, file-system locks) are why the advice is to reach for a REENTRANT lock when a hot synchronized block shows up under load, and why you should never drive a virtual thread through ThreadMXBean-heavy operations casually. POOLING virtual threads is an anti-pattern — they are cheap precisely because each is fresh and short-lived; an executor of 100 virtual threads has reinvented a platform pool with worse diagnostics. And the resource-protection rule survives the switch unchanged: one virtual thread per request means unbounded CONCURRENCY against downstream resources — the database does not care that your threads are cheap. Bound the DATABASE work with a semaphore or a small connection pool (the next section) — that is HikariCP's second job in a virtual-thread world.
                                
                                One operational footnote before moving on: virtual threads are VISIBLE in thread dumps and JFR (the terminal companion shows where they land in `jcmd Thread.print`), and `Thread.currentThread().toString()` names both the virtual thread and its carrier — the evidence the topic-02 demo surfaced. Observable machinery is fixable machinery; the runtime did not trade away your diagnostics to buy the concurrency.""")),
                section("hikaricp-pool", "HikariCP: the connection pool is a budget",
                        "Connections are capped by the database, not by appetite. The pool's job is to make that cap a deliberate, measurable budget — and this app's eight connections are a decision you can read.",
                        text("hk:why", """
                                A database connection is expensive to CREATE (TCP handshake, TLS, backend process fork or authentication, session state) and expensive to HOLD (every one is a slot in the server's max_connections, memory on both ends, and — on Postgres — a backend process). The pool's existence answers the first cost (create N once, reuse forever); its SIZE answers the second, and that is the part teams get wrong in the loud direction: MORE CONNECTIONS DOES NOT MEAN MORE THROUGHPUT. Past the point where the database's cores are busy, additional active connections buy contention — lock waits, buffer-cache thrash, context switching — and throughput goes DOWN while latency climbs. The pool is not a bucket you fill because fast sounds good; it is a BUDGET you set against the database's actual capacity.
                                
                                The honest sizing arithmetic, for the record: a database serving a mixed OLTP load goes parallel at roughly CORES active queries (call it 2x cores to absorb I/O waits), so a 4-core Postgres is saturated somewhere near 8–16 active connections — and a hundred-client app pool against it is a queue with extra steps. HikariCP's own guidance reduces to connections ≈ core_count times 2 plus effective_spindle_count, with the modern amendment that "spindles" is mostly a proxy for I/O wait you should MEASURE rather than guess. This guide's server runs exactly the modest budget the datasource block declares:"""),
                        code("hk:yml", "yaml",
                                "server/src/main/resources/application.yml — the pool as a read decision: 8 against Postgres's 100",
                                "server/src/main/resources/application.yml", """
                                datasource:
                                  url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}
                                  # … username and password ride the same twelve-factor placeholders
                                  hikari:
                                    maximum-pool-size: 8"""),
                        text("hk:behavior", """
                                Eight, against PostgreSQL's default max_connections of 100 — deliberately small, with the comment in the file naming the shared budget (dev tools and the Testcontainers siblings of the test suite draw from the same 100). What the reader should internalize is what happens when the budget EXHAUSTS: a request needing a connection waits up to connection-timeout (default 30s — far too long to feel like anything but a hang) and then fails with a typed SQLTransientConnectionException. So the pool's runtime story is a QUEUE, and queueing is INVISIBLE unless you measure it — which is why the two numbers that make a pool observable are its WAIT (hikaricp_connections_acquire_state via Micrometer; time from ask to granted) and its USAGE (active versus idle). A pool permanently at 8/8 active with growing wait is not a bug in the pool; it is a measurement saying the DATABASE is the constraint — and the fix is on the query side (the jdbc topic's EXPLAIN ANALYZE, the jpa topic's N+1 eliminations) or the caching side, not a bigger pool.
                                
                                Two production habits complete the section. LEAK DETECTION: a connection checked out and never returned (a missing close, an exception path that skips the finally) starves the pool one request at a time until every request waits — HikariCP's `leak-detection-threshold` (spring.datasource.hikari.leak-detection-threshold, a ms budget after which a still-held connection logs a stack trace naming its borrower) turns that slow bleed into a loud log line; enable it in any environment you do not trust. TIMEOUT HONESTY: set maximumPoolSize AND connectionTimeout deliberately so that "the database is gone" fails in seconds (a typed 503 through the error taxonomy) instead of hanging a request — and in a virtual-thread world remember the pairing from last section: cheap request threads make the small pool MORE correct, not less, because the pool is the semaphore that protects the database from your concurrency. The pool is the last place where "more" reliably means "worse" — treat its size like a budget review, not a knob.""")),
                section("gc-g1-zgc", "GC: G1, ZGC, and knowing when to stop",
                        "The collector is a dial with two good defaults — G1's bounded pauses and ZGC's concurrent ones — and one rule that outranks both: reduce allocation before you tune collection.",
                        text("gc:model", """
                                The JVM topic covered the mechanics (generations, the weak generational hypothesis, young/old collections); performance work needs the DECISION layer. On JDK 25 the default collector is GARBAGE-FIRST (G1): the heap carved into regions, collection ordered by region payoff ("garbage-first" — collect the regions that free the most for the least work), young collections run in parallel with a pause bounded by a soft GOAL you can set (-XX:MaxGCPauseMillis, default 200ms), and mixed collections shrink the old generation incrementally instead of one stop-the-world avalanche. For the majority of services — heaps from a few hundred MB to a few tens of GB, pause tolerance in the tens-to-hundreds of milliseconds — G1 with its defaults is not a compromise; it is a well-engineered default you should have METRICS-based reasons to leave.
                                
                                The reason to leave it is a PAUSE SLA G1 cannot meet at your heap size — and the destination is ZGC: a CONCURRENT collector that does almost all of its work while your application threads keep running (sub-millisecond stops, roughly constant in heap size), generational since JDK 21, and the deliberate choice when the heap is large (tens of GB+) or the latency budget is single-digit milliseconds. The trade is throughput (concurrent GC steals CPU from your application continuously rather than in bursts) and footprint (ZGC likes a somewhat larger heap for the same load — give it headroom). The old decision table's third row — Parallel GC for pure throughput batch jobs — still exists but reads today as "you have a batch window and no users on the line", which is rarer than it used to be.
                                
                                And now the rule that outranks the table: GC TUNING CANNOT RESCUE AN ALLOCATION STORM. A service that allocates gigabytes per second will pause, spill, and page no matter which collector flag you chant — the durable fix is allocating LESS (reuse buffers, right-size collections — the streaming topic's chunked paths instead of whole-body byte arrays, the jpa topic's DTO projections instead of hydrated entity graphs), and the second-most-durable is a heap sized so the working set fits comfortably (-Xmx/-Xms equal to stop resize pauses; in containers, -XX:MaxRAMPercentage against the cgroup limit rather than a bare -Xmx, so the JVM and the orchestrator agree on the memory the pod actually has). Allocation rate and live-set size are JFR's two most actionable recordings — the terminal tour shows reading both — and each flags change you make should be traceable to one of them, or to a measured pause, or it is incantation.""")),
                section("performance-checklist", "The performance checklist",
                        "Every lever from this topic, in the order measurement says they pay. Work top to bottom, stop when the budget is met.",
                        text("pc:order", """
                                The levers, in the order they pay: ONE — MEASURE (JFR plus the metrics layer): name the slow thing and where its time goes; no number, no change. TWO — FIX THE ALGORITHM AND THE QUERY COUNT: the biggest wins are always structural — the N+1 the jpa topic eliminated, the missing index the sql topic's EXPLAIN revealed, the O(n-squared) loop, the payload ten times bigger than its reader uses. A 10x here outruns every flag below. THREE — CACHE WHAT IS REUSED, at the layer with the best hit economics (the caching topic's decision tree: HTTP caching for shared immutables, @Cacheable for computed reads, never caching what you cannot invalidate). FOUR — RIGHT-SIZE THE CONCURRENCY: thread hygiene and virtual threads for overlapped waits, the HikariCP budget for the database, semaphores for every other downstream that cannot take your full concurrency. FIVE — MOVE BYTES: compression (this topic's benchmark), pagination instead of kitchen-sink payloads, streaming for the large bodies that cannot shrink. SIX — AND ONLY NOW THE JVM: heap sizing, then G1/ZGC as a pause decision, then stop.
                                
                                Around the list, three habits keep it honest. BUDGETS BEFORE WORK: "faster" is not a goal — "p95 under 200ms at 500 rps" is, and it tells you when to STOP (the most underrated performance skill; every optimization past the budget spends risk for nothing). REGRESSION GUARDS: the numbers that motivated a change belong in a test (the benchmark's byte-count assertions are the pattern; timing assertions are not) or a dashboard alert, so the win cannot silently rot. And CAPACITY IS A PERFORMANCE QUESTION: the scaling topic takes this further, but load average against cores, pool usage against pool size, and heap against limit are the same numbers read as headroom — performance is what you measure at today's load; capacity is whether tomorrow's load survives it. Work the list, keep the receipts, and resist the famous lever: the checklist's first item exists because the last three items are where teams spend their time and the second item is where the money is.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic curled: run the benchmark from curl, prove compression on the wire, then take JFR, jcmd, and the GC flags for a real tour.",
                        text("tc:intro", """
                                Every lever in this topic has a terminal twin, and they compose into one session. The benchmark is one GET — the same thin controller shape as every demo in the guide:
                                """),
                        code("tc:controller", "java",
                                "guide/presentation/demo/PerfDemoController.java — parameters as raw strings, one use case, Result mapped to HTTP",
                                "server/src/main/java/guide/presentation/demo/PerfDemoController.java", """
                                @GetMapping(value = "/api/demo/perf/bench", produces = MediaType.APPLICATION_JSON_VALUE)
                                RunCompressionBenchmark.BenchReport bench(
                                        @RequestParam(name = "payload", required = false) String payload,
                                        @RequestParam(name = "passes", required = false) String passes) {
                                    return unwrap(runCompressionBenchmark.execute(payload, passes));
                                }"""),
                        text("tc:tour", """
                                Run it at two sizes and compare the ratios; then prove the wire side with the connector itself — same endpoint, two Accept-Encoding headers, byte counts from curl's own counters:
                                """),
                        code("tc:curl-bench", "bash",
                                "the benchmark, then the wire proof",
                                null, """
                                # the benchmark: identity bytes, per-level results, effective posture, reading
                                curl -s 'localhost:8080/api/demo/perf/bench?payload=large&passes=64' | python3 -m json.tool | head -30
                                # {
                                #   "payloadProfile": "large", "payloadBytes": 16651, "payloadItems": 44,
                                #   "levels": [
                                #     {"level": 1, "rawBytes": 16651, "compressedBytes": 4299,
                                #      "compressedToRawRatio": 0.258, "medianCompressMicros": 61, ...},
                                #     {"level": 6, "rawBytes": 16651, "compressedBytes": 3980, ...}],
                                #   "serverCompression": {"enabled": true, "mimeTypes": ["application/json", ...],
                                #     "minResponseSize": "1KB"},
                                #   "reading": ["identity: 16651 bytes leave uncompressed — ...", ...]
                                # }

                                # the wire proof — the real connector, the same document, two encodings:
                                curl -s -H 'Accept-Encoding: identity' -o /dev/null \\
                                  -w 'identity: %{size_download} bytes\n' localhost:8080/api/content
                                curl -s -H 'Accept-Encoding: gzip' -o /dev/null \\
                                  -w 'gzip:     %{size_download} bytes\n' localhost:8080/api/content
                                # identity: 9837 bytes
                                # gzip:     2064 bytes   <- the negotiated trade, measured on the wire

                                # the floor: a small body rides identity — compressing it would lose money
                                curl -s -H 'Accept-Encoding: gzip' -o /dev/null \\
                                  -w 'health: %{size_download} bytes (under 1KB — identity wins)\n' localhost:8080/actuator/health"""),
                        text("tc:jfr-tour", """
                                Then the measurement tools themselves — find the process, record with JFR at production-safe overhead, and read the two recordings that matter most (allocation rate and live set) plus the sampled hot path:
                                """),
                        code("tc:jfr", "bash",
                                "JFR: start, dump, read — the measure-first tour",
                                null, """
                                # every JVM jcmd can see (find your pid here)
                                jcmd | grep guide

                                # start a bounded recording with the default, production-safe event set
                                jcmd <pid> JFR.start name=perf-tour duration=60s filename=/tmp/perf-tour.jfr
                                # need it NOW, mid-incident? dump what has been recorded so far:
                                jcmd <pid> JFR.dump name=perf-tour filename=/tmp/perf-tour-now.jfr

                                # read: CPU load and GC frequency first — the shape before the detail
                                jfr print --events jdk.CPULoad,jdk.GarbageCollection /tmp/perf-tour.jfr | head -20
                                # the allocation question (rule one of the GC section):
                                jfr print --events jdk.ObjectAllocationSample /tmp/perf-tour.jfr | grep 'objectClass' | sort | uniq -c | sort -rn | head -10
                                # the hot path: which methods own the sampled stacks?
                                jfr print --events jdk.ExecutionSample /tmp/perf-tour.jfr | grep -A4 'stackTrace' | head -40
                                # … or open the .jfr in JDK Mission Control for the flame views"""),
                        code("tc:threads-and-gc", "bash",
                                "thread dumps and the collector, from the outside",
                                null, """
                                # the thread census — platform carriers and (since this app runs on them) the
                                # request-serving virtual threads, parked while they wait:
                                jcmd <pid> Thread.print | head -40

                                # what is the collector actually doing, on the running process?
                                jcmd <pid> GC.heap_info

                                # the pause shape of the default collector, from a scratch run:
                                java -Xlog:gc -jar target/*.jar 2>&1 | grep 'Pause' | head -10
                                #   [info][gc] GC(12) Pause Young (Normal) ... 23M->3M(256M) 2.843ms

                                # the low-pause alternative — same app, ZGC, generous heap:
                                java -XX:+UseZGC -Xms2g -Xmx2g -Xlog:gc -jar target/*.jar 2>&1 | grep -c 'Pause'

                                # which collector and heap would this JVM ACTUALLY use? ask, do not guess:
                                java -XX:+PrintFlagsFinal -version | grep -E 'UseG1GC|UseZGC|MaxHeapSize' | head"""),
                        text("tc:close", """
                                That last command is the topic's last lesson in miniature: PRINTFLAGSFINAL before opinions, JFR before changes, the benchmark before the lever — ask the process, then act on the answer. You now hold the full loop this topic opened with: metrics name the slow thing, JFR says where the time lives, the checklist orders the levers (algorithm, cache, concurrency, bytes, and only then the JVM), and the tools in this tour turn every claim into a receipt. The reliability topic takes the natural next step — the same measurement discipline applied to failure: when the slow thing is not slow but GONE, timeouts, retries, and circuit breakers are the levers, and their order is learned the same way.""")));
    }
}
