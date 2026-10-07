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
 * Topic 02 — JVM Fundamentals. What actually runs your jar: bytecode,
 * classloading, and the JIT; threads versus the event loop; the memory
 * model; the byte/char streaming stack; the NIO file API; the toolchain;
 * process environment and signals; executors and futures; garbage
 * collection; and the pool-vs-virtual decision. Two live demos ride inline
 * in the sections that teach them (jvm:threads, jvm:streaming), and every
 * code block is quoted from this repo's real source, verified mechanically
 * by SnippetSourcesTest.
 */
public final class JvmTopic {

    private JvmTopic() {
    }

    public static Topic topic() {
        return new Topic("jvm", "JVM Fundamentals",
                "What actually runs your jar: bytecode and the JIT, real threads versus the "
                        + "event loop, the memory model, streams and charsets, GC — the machine "
                        + "underneath every Spring Boot server.",
                TopicGroupId.FUNDAMENTALS, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-the-jvm-is", "What the JVM is",
                        "Java source is not what runs. The JVM is — bytecode, classloading, and a JIT that makes hot code fast. This chain explains startup, warm-up, and the jar you ship.",
                        text("jvm:bytecode", """
                                Concept first: the JVM never runs your .java files — it runs .class files of JVM bytecode, a portable, stack-based instruction format javac produces at compile time. The same artifact runs anywhere a JVM exists. At startup the classloader chain (application, platform, bootstrap) finds each class lazily — load, link, initialize on first touch — which is why a JVM process warms up rather than springs ready.

                                What executes the bytecode is tiered compilation. The interpreter starts a method immediately, counting invocations; hot methods graduate to the just-in-time compilers — C1 for quick, moderately optimized native code, C2 for heavily optimized code shaped by live profiling. JVM code starts slower than pre-compiled binaries and gets faster over its first minutes. For a server that runs for months you pay warm-up once and collect peak speed forever; for a CLI or scale-to-zero the same warm-up is a real cost — a design decision, not a detail."""),
                        text("jvm:boot-jar", """
                                This app is one such process. The entry point below is the whole of GuideApplication.java: a main that hands control to SpringApplication (topic 04 dissects that call). What Maven assembles from it is the piece worth understanding here: Spring Boot's executable jar — your classes under BOOT-INF/classes, every dependency under BOOT-INF/lib, and a manifest whose Main-Class is Boot's own launcher, not your code. The launcher bootstraps a classloader over that layout and THEN calls your main — which is why java -jar server.jar works on a machine with no Maven and an empty CLASSPATH: the jar is the whole runtime classpath, zipped. And because dependencies live in their own layer, a Docker image can cache "libraries that never change" separately from "your code that changes every build" — the deployment topic cashes that in. The plugin doing the packaging is one declaration in the build you met in topic 01:"""),
                        code("jvm:app", "java",
                                "server/src/main/java/guide/GuideApplication.java — the process entry point, verbatim",
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
                        code("jvm:pom-plugin", "xml",
                                "server/pom.xml — one plugin declaration turns the build output into the executable boot jar",
                                "server/pom.xml", """
                                <build>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                        </plugin>
                                    </plugins>
                                </build>""")),
                section("threads-vs-event-loop", "Threads versus the event loop",
                        "Node sold one thread and an event loop; the JVM's answer is many real threads — and since JDK 21, millions of cheap virtual ones. Same problem, waiting; two honest solutions.",
                        text("thr:contrast", """
                                The Express guide in this family spends a whole topic on the event loop: one JavaScript thread, non-blocking callbacks, and the cardinal sin — never block the loop, because everything else queues behind you. That design turns waiting into callbacks because threads are too expensive to waste on waiting.

                                The JVM made the opposite bet for its first quarter century: many threads. Every request gets its own thread with its own call stack, and blocking is FINE — a thread waiting on a socket simply stops being scheduled while every other thread proceeds. The cost is the thread itself: a platform thread maps one-to-one onto an operating-system thread, reserves roughly a megabyte of stack by default, and pays a real context-switch price — so servers bounded them with pools, and sizing the pool became the capacity planning: too small and requests queue, too big and you meet the "unable to create new native thread" OutOfMemoryError from the memory section."""),
                        text("thr:virtual", """
                                JDK 21 changed the arithmetic with virtual threads (JEP 444). A virtual thread is a thread the JVM implements itself: when it blocks on I/O the runtime parks it — saving its stack, no OS thread involved — and immediately reuses the OS thread (its carrier) for another virtual thread. Write straightforward blocking code; get event-loop-scale concurrency. Creating one costs bytes, not megabytes, so the Node world's "never block" discipline stops applying to request handling.

                                The demo runs the same N blocking tasks through both models and measures what differs. Platform: a fixed pool of four OS threads, tasks queueing in waves — wall time grows as tasks divided by pool size. Virtual: one thread per task, all blocked at once, all parked — wall time is one blocking period plus change. And the carrier shows through: on a virtual thread, Thread.currentThread().toString() reports the virtual thread AND the carrier it is mounted on — evidence visible in the demo's sample names."""),
                        code("thr:virtual-code", "java",
                                "guide/domain/jvm/ThreadsExhibit.java — the virtual run: one executor line, try-with-resources owns the lifecycle",
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
                        text("thr:builder", """
                                For one-off async work you do not even need an executor — the builder API starts a single named thread in one expression. Naming matters: the default virtual thread name is VirtualThread[#id], readable but anonymous; a name like guide-worker-0 turns a thread dump from archaeology into a phone book. The same builder exists for platform threads — Thread.ofPlatform().

                                Run the demo and read the numbers against this section: distinct thread names bounded by four on the platform side, equal to the task count on the virtual side, the carrier in the name. At 100 tasks the pool needs twenty-five waves while the virtual executor still takes one sleep — that gap is the entire argument."""),
                        code("thr:builder-code", "java",
                                "guide/domain/jvm/ThreadsExhibit.java — Thread.ofVirtual(): build, name, start, join — no executor needed",
                                "server/src/main/java/guide/domain/jvm/ThreadsExhibit.java", """
                                public static String namedVirtualHello() {
                                    String[] observed = new String[1];
                                    Thread worker = Thread.ofVirtual().name("guide-worker-", 0).start(() -> {
                                        observed[0] = Thread.currentThread().toString();
                                    });
                                    // … join with interrupt hygiene elided — same shape as any join …
                                    return observed[0];
                                }"""),
                        demo("thr:demo", "jvm:threads", "Threads vs event loop, live",
                                "Run N simulated blocking tasks twice — a fixed pool of 4 platform threads versus one virtual thread per task — and compare wall time, distinct thread names, sample names, and the carrier thread visible inside each virtual thread's toString().")),
                section("memory-model", "The memory model: heap, stacks, metaspace",
                        "Every thread owns a stack; all threads share the heap; classes live in metaspace. Each OutOfMemoryError flavor names a different disease — read it before tuning anything.",
                        text("mem:layout", """
                                The runtime memory picture is three zones with different owners. Each thread has a stack: call frames, locals, and evaluation space, sized by -Xss (about a megabyte by default) and created with the thread — a thousand threads means a thousand stacks, why platform threads never scaled per-connection. Infinite recursion exhausts it, and StackOverflowError is the honest crash: the fix is the algorithm, not the flag.

                                All threads share the heap: every new object lives there, reclaimed by the collector when reachable from nowhere. Class metadata — loaded classes, bytecode, annotations — lives in metaspace, allocated off the heap from native memory; it replaced PermGen in Java 8, which had to be sized by hand, while metaspace grows with only MaxMetaspaceSize as a guardrail.

                                One nuance worth internalizing early: heap allocation is CHEAP. Each thread allocates into its own TLAB (thread-local allocation buffer), so an ordinary allocation is a pointer bump — a few instructions, no lock. Reclamation, not allocation, is what costs; code that "avoids allocations" in ordinary request paths is optimizing the wrong half of the equation. Allocation matters in the hot loops the JIT cares about, which the performance topic measures rather than guesses."""),
                        text("mem:generations", """
                                Inside the heap the collector exploits one empirical regularity — the generational hypothesis: most objects die young. A request's scratch objects, a stream pipeline's intermediate lists, the DTO that outlives one response — allocated, used, garbage within milliseconds. So the heap is organized by age: new objects allocate into the young generation (eden plus two survivor spaces), and a minor collection copies the few survivors aside and reclaims eden wholesale in microseconds, precisely because it ignores everything old. Objects that survive a few minors promote to the old generation, collected less often with heavier machinery.

                                The practical reading: short-lived garbage is nearly free; it is LONG-lived growth you watch — unbounded caches, session maps, accumulating collections. A "leak" is exactly that: objects that stopped being useful but never stopped being reachable."""),
                        text("mem:oom", """
                                OutOfMemoryError is not one disease but a family, and the message text is the diagnosis — each flavor names a different zone and a different fix. "Java heap space": the live set does not fit — real load, or a leak of reachable-but-dead objects; take a heap dump (the debugging topic walks it) before touching -Xmx. "GC overhead limit exceeded": the collector spends nearly all CPU reclaiming almost nothing — the endgame of a leak. "Metaspace": classloader leaks, usually generated classes nothing unloads. "Unable to create new native thread": not the heap at all — thread exhaustion, the platform-thread ceiling the previous section described; the fix is fewer threads (or virtual ones), not more memory. "Direct buffer memory": off-heap NIO buffers, bounded separately by -XX:MaxDirectMemorySize.

                                The discipline: read the message, identify the zone, THEN act — a bigger -Xmx at "GC overhead limit exceeded" changes the size of the bucket, not the hole in it, and hides the evidence while the leak grows.""")),
                section("streams-readers-channels", "Streams, readers, and channels",
                        "Bytes and chars are different types for a reason. The classic bug — decoding a multi-byte character across a buffer boundary — demonstrated safely, plus where channels fit.",
                        text("str:two-hierarchies", """
                                The java.io world splits cleanly in two. InputStream and OutputStream move BYTES — raw, undecoded. Reader and Writer move CHARACTERS — decoded text, which required choosing a charset to produce. The bridge is InputStreamReader: bytes in, a named charset, chars out. That explicitness is the entire discipline of text on the JVM: bytes have no encoding, so every bytes-to-String conversion commits to a charset somewhere, whether you wrote it or the platform guessed. Since JDK 18 (JEP 400) the platform default is UTF-8 — still a default: name the charset at every boundary your API owns, because your contract with the client is not "whatever this machine prefers". This repo spells it StandardCharsets.UTF_8 everywhere it reads text — see the nio section.

                                The other discipline is buffering: wrap ONCE, at the layer that talks to the OS. An unbuffered read() per iteration is a syscall per iteration; BufferedInputStream (or Files.readString) batches them. Wrapping zero times in a loop is how a thousand-line file becomes a hundred thousand syscalls."""),
                        text("str:the-bug", """
                                Now the bug this section's demo exists to make safe. A multi-byte character — UTF-8 encodes the arrow → (U+2192) as three bytes — can straddle any boundary: a read() returns whatever is available, not whole characters. Decode each chunk independently with new String(bytes, 0, n, charset) and each half of the split character becomes U+FFFD, the replacement character — mojibake, silently, no exception. The fix is structural: a Reader holds the decoder's state ACROSS reads, so a partial character at the end of one read is completed by the bytes of the next. Decode in exactly one place, on a Reader, with a named charset; count and checksum bytes on streams, where no decoding happens at all.

                                The exhibit runs one payload through four consumers: byte scan and channel scan count it identically, the Reader decodes clean, and the naive per-chunk decode — boundaries forced inside the '→' characters — returns damaged text with the damage counted. Channels are the same lesson in another wrapper: ReadableByteChannel reads into ByteBuffer, the NIO API files and sockets speak — same bytes, same boundary rules."""),
                        code("str:reader-code", "java",
                                "guide/domain/jvm/StreamingExhibit.java — the Reader loop: decode across boundaries, count chars, never a split character",
                                "server/src/main/java/guide/domain/jvm/StreamingExhibit.java", """
                                try (Reader reader = new InputStreamReader(
                                        new TrickleInputStream(payload, bufferSize), charset)) {
                                    char[] buffer = new char[bufferSize];
                                    int read;
                                    while ((read = reader.read(buffer)) != -1) {
                                        for (int index = 0; index < read; index++) {
                                            totalChars++;
                                            if (buffer[index] == MULTIBYTE) {
                                                multibyteChars++;
                                            }
                                            if (buffer[index] == '\\uFFFD') {
                                                replacementChars++;
                                            }
                                        }
                                    }
                                }"""),
                        code("str:naive-code", "java",
                                "guide/domain/jvm/StreamingExhibit.java — the bug: new String per read, no decoder state across boundaries",
                                "server/src/main/java/guide/domain/jvm/StreamingExhibit.java", """
                                try (InputStream in = new TrickleInputStream(payload, bufferSize)) {
                                    byte[] buffer = new byte[bufferSize];
                                    int read;
                                    while ((read = in.read(buffer)) != -1) {
                                        String piece = new String(buffer, 0, read, charset);
                                        replacementChars += countReplacements(piece);
                                        decoded.append(piece);
                                    }
                                }"""),
                        demo("str:demo", "jvm:streaming", "Streams, readers, and the split-character bug, live",
                                "Generate a payload of chunks and consume it four ways — raw InputStream, NIO channel, Reader, and a naive per-chunk decode — with read boundaries forced inside multi-byte '→' characters. Watch the Reader come out clean while the naive decode returns replacement characters, in UTF-8 or UTF-16.")),
                section("nio-file", "The NIO file API: Path and Files",
                        "Path is path algebra, Files does everything — and this repo already ships real code using both. Walking is a lazy stream you must close; temp files are the honest scratch space.",
                        text("nio:path-files", """
                                Since Java 7 the file API is Path and Files, and the old java.io.File is legacy you should stop writing. The split is clean: Path is an immutable, algebraic path value — resolve (append), relativize (from here to there), normalize (collapse . and ..) — with no I/O and no filesystem contact; Files is the static surface that DOES touch the disk — readString, writeString, size, exists, createDirectories, walk, createTempDirectory. Because Path is a pure value, composing and testing path logic needs no filesystem; because Files methods are named one-shots, the intent reads at the call site.

                                This is not hypothetical — the snippet verifier you met in topic 01 lives on this API. SnippetSourcesTest builds a repo-root Path, resolves each block's source against it, guards traversal with normalize + startsWith, and reads the file with an EXPLICIT UTF-8 charset — the streaming section's discipline, applied to files:"""),
                        code("nio:real-code", "java",
                                "server/src/test/java/guide/content/SnippetSourcesTest.java — the repo's real Path/Files usage: resolve, traversal guard, UTF-8 read",
                                "server/src/test/java/guide/content/SnippetSourcesTest.java", """
                                private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
                                // …
                                Path absolute = REPO_ROOT.resolve(source).normalize();
                                if (!absolute.startsWith(REPO_ROOT)) {
                                    return ref.topicId() + "/" + block.id() + ": source must resolve under the repo root: " + source;
                                }
                                if (!Files.isRegularFile(absolute)) {
                                    return ref.topicId() + "/" + block.id() + ": source file not found: " + source;
                                }
                                String sourceText;
                                try {
                                    sourceText = Files.readString(absolute, StandardCharsets.UTF_8);
                                } catch (IOException | UncheckedIOException error) {
                                    return ref.topicId() + "/" + block.id() + ": source unreadable: " + source + " (" + error + ")";
                                }"""),
                        text("nio:walk", """
                                Two pieces of the API carry their own pitfalls. Files.walk traverses a tree as a LAZY stream — the directory handle stays open until the stream closes, so it belongs in try-with-resources or you leak a handle per walk. Files.createTempDirectory makes scratch space that is genuinely yours — unique, in the system temp dir, better than inventing names by hand — but it does NOT delete itself: the finally block that removes the tree is part of the pattern, not optional hygiene. Deletion is deepest-first, because a directory must be empty before it can be deleted.

                                The teaching exhibit below is the whole pattern in one method, honest about its status: the app's own file handling arrives with the files-media topic (uploads are streams!), at which point real code replaces this exhibit as the quoted source. Until then it is the only Files.walk in the repo, and it cleans up after itself:"""),
                        code("nio:exhibit-code", "java",
                                "guide/domain/teaching/ExhibitNioFile.java — temp tree, census, deepest-first cleanup in finally",
                                "server/src/main/java/guide/domain/teaching/ExhibitNioFile.java", """
                                public static WalkReport walkTempTree() {
                                    try {
                                        Path root = Files.createTempDirectory("guide-nio-exhibit");
                                        try {
                                            Files.writeString(root.resolve("notes.md"), "# temp tree\\n");
                                            Files.createDirectories(root.resolve("chapters/jvm"));
                                            // … two more writeString calls build the sample tree …
                                            return walk(root);
                                        } finally {
                                            deleteRecursively(root);
                                        }
                                    } catch (IOException error) {
                                        throw new UncheckedIOException("nio exhibit failed", error);
                                    }
                                }""")),
                section("toolchain", "The toolchain: javac, jar, jlink, jcmd, jshell",
                        "Maven wraps the compilers and packagers, but the underlying knives are worth holding once by hand — especially jcmd, which talks to the RUNNING process.",
                        text("tool:knives", """
                                The JDK ships its whole toolchain, and Maven is a coordinator over it, not a replacement: mvn compile is javac with the classpath resolved for you; mvn package is jar with a manifest and the Boot layering applied. Knowing them means knowing what Maven does on your behalf — and being able to do it once bare when the build is under suspicion.

                                The ones worth meeting: javac (source to bytecode), java (run a class or jar), jar (zip with a manifest), javap (disassemble bytecode — what the compiler ACTUALLY produced), jlink (cut a stripped runtime image with only the modules you use), jcmd (commands to a RUNNING process: thread dumps, heap info, GC operations — topic 35's debugging workflow is mostly jcmd plus a profiler), and jshell (a REPL: try an API without a project or an IDE). jshell deserves a habit: it is the cheapest experiment bench you own — "does String.format zero-pad?" Do not guess; ask the JVM. The session below runs in the container this guide builds in."""),
                        code("tool:jshell-tour", "bash", "the toolchain by hand — javap on this app's own class, the boot jar's layout, a jshell experiment", null, """
                                # What javac actually produced for main() — the bytecode is readable:
                                cd server && javap -c target/classes/guide/GuideApplication.class | head -20

                                # The boot jar layout from the first section, with your own eyes:
                                jar tf target/server-0.0.1-SNAPSHOT.jar | head -12
                                # → BOOT-INF/classes/guide/GuideApplication.class, BOOT-INF/lib/…, META-INF/MANIFEST.MF

                                # The cheapest experiment bench in the JDK:
                                jshell -q <<'EOF'
                                System.out.println(String.format("%05d", 42));
                                Thread.ofVirtual().name("probe-", 0).start(() ->
                                        System.out.println(Thread.currentThread())).join();
                                /exit
                                EOF

                                # And talking to a RUNNING process (start the server first, then):
                                jcmd $(jps -q | head -1) GC.heap_info""")),
                section("process-env-signals", "Process, environment, and signals",
                        "Environment variables are the twelve-factor config channel; SIGTERM is how the world stops your process. Read env once, validate at startup, and let shutdown hooks do the last cleanup.",
                        text("proc:env", """
                                A server process meets its configuration through the environment — environment variables in Docker and Kubernetes, the twelve-factor model the environments topic expands on. System.getenv() reads it, but two disciplines matter more than the call. Read ONCE, at startup: environment is process state captured at launch, and treating it as live configuration invites inconsistency — this app's demo endpoints flip at runtime through the scenario store precisely because that is a DIFFERENT channel, designed for mutation. And validate at startup, fail fast: a bad value must kill the process at boot — loudly, with the variable's name in the message — not surface at 3 a.m. as a request failure three layers from the cause.

                                This app's scenario state is the canonical in-repo example of both. The composition root reads the two SCENARIO_* variables through Spring's Environment abstraction — an injectable view over system properties, env vars, and profiles, the testable seam (System.getenv() directly would glue the test to the machine) — and hands them to ScenarioState.fromEnvironment, which defaults sensibly and throws on garbage:"""),
                        code("proc:env-state", "java",
                                "guide/core/scenario/ScenarioState.java — env parsing with defaults and fail-fast validation",
                                "server/src/main/java/guide/core/scenario/ScenarioState.java", """
                                public static ScenarioState fromEnvironment(String rawDataMode, String rawInjectFailure) {
                                    return new ScenarioState(parseDataMode(rawDataMode), parseInjectFailure(rawInjectFailure));
                                }

                                private static ScenarioDataMode parseDataMode(String raw) {
                                    String value = raw == null || raw.isEmpty() ? ScenarioDataMode.FAKE.wireName() : raw;
                                    try {
                                        return ScenarioDataMode.fromWire(value);
                                    } catch (IllegalArgumentException e) {
                                        throw new IllegalStateException(
                                                "Invalid SCENARIO_DATA_MODE \\"" + value + "\\". Expected one of: fake, live.", e);
                                    }
                                }"""),
                        code("proc:env-wire", "java",
                                "guide/config/GuideConfig.java — the composition root reads env through Spring's Environment, once, at startup",
                                "server/src/main/java/guide/config/GuideConfig.java", """
                                @Bean
                                ScenarioStore scenarioStore(Environment environment) {
                                    return new ScenarioStore(ScenarioState.fromEnvironment(
                                            environment.getProperty("SCENARIO_DATA_MODE"),
                                            environment.getProperty("SCENARIO_INJECT_FAILURE")));
                                }"""),
                        text("proc:signals", """
                                Signals are how the outside world talks to the process: SIGTERM asks it to stop (docker stop, Kubernetes pod termination, systemd); SIGKILL does not ask. The JVM intercepts SIGTERM and runs every registered shutdown hook — Runtime.addShutdownHook(new Thread(...)) — before exiting: the last code you control, where a server finishes writing, closes pools and connections, and says goodbye. Register sparingly: hooks run concurrently and must not deadlock, and any hook can be cancelled — removeShutdownHook is how frameworks roll back a half-finished startup.

                                The exhibit below exercises the registration lifecycle safely: register a hook, remove it, report. Spring Boot wraps this whole subject — bean destroy callbacks, SmartLifecycle stops, graceful request draining (server.shutdown: graceful finishes in-flight requests before the context closes) — and the deployment topic wires it against docker stop's timeout. What Boot cannot change is the physics: cleanup happens inside the grace period, or SIGKILL ends the conversation."""),
                        code("proc:hook", "java",
                                "guide/domain/teaching/ExhibitShutdownSignal.java — register, then remove: the hook lifecycle without exiting the JVM",
                                "server/src/main/java/guide/domain/teaching/ExhibitShutdownSignal.java", """
                                public static String registerAndRemove(String label) {
                                    String name = "shutdown-" + (label == null || label.isBlank() ? "demo" : label.trim());
                                    Thread hook = new Thread(() -> System.out.println("SIGTERM cleanup: " + name), name);
                                    Runtime runtime = Runtime.getRuntime();
                                    runtime.addShutdownHook(hook);
                                    boolean removed = runtime.removeShutdownHook(hook);
                                    return "hook \\"" + name + "\\" registered"
                                            + (removed ? ", then removed — it will not run at exit" : " — removeShutdownHook returned false");
                                }""")),
                section("concurrency-patterns", "Concurrency patterns: executors and futures",
                        "Pools own threads so you never start them by hand; CompletableFuture turns async work into a composable value. Lifecycle discipline is the whole difference between a pool and a leak.",
                        text("conc:lifecycle", """
                                Topic 01 introduced the ExecutorService as "the abstraction that grows up"; the JVM-level view adds WHY it exists. Threads are expensive process resources (the memory section priced them), and an executor is the boundary that owns them: you submit work, the pool decides which thread runs it and what happens to the thread after. The lifecycle has a shape people skip at their peril: shutdown() stops accepting work and lets queued tasks drain, awaitTermination() waits with a timeout, shutdownNow() interrupts what remains. Skip the shape and the pool outlives your method — its threads are non-daemon by default, so the JVM cannot exit, and a test run hangs on a pool nobody shut down. The rule this repo teaches: shutdown in finally, always, the way you close a stream.

                                The other half of the discipline is sizing — the pools-versus-virtual decision two sections down: CPU-bound pools want roughly one thread per core (more just adds context-switching); a fixed pool serving blocking I/O is a queue with a wait time, sized by latency budget, not cores."""),
                        code("conc:pool-code", "java",
                                "guide/domain/teaching/ExhibitConcurrency.java — submit, collect Futures, shut down in finally",
                                "server/src/main/java/guide/domain/teaching/ExhibitConcurrency.java", """
                                public static long pooledSum(int tasks, int perTask) throws Exception {
                                    ExecutorService pool = Executors.newFixedThreadPool(4);
                                    try {
                                        List<Future<Long>> futures = new ArrayList<>();
                                        for (int index = 0; index < tasks; index++) {
                                            futures.add(pool.submit(() -> (long) perTask));
                                        }
                                        long total = 0;
                                        for (Future<Long> future : futures) {
                                            total += future.get();
                                        }
                                        return total;
                                    } finally {
                                        pool.shutdown();
                                        // … awaitTermination + shutdownNow elided — never exit
                                        // without offering the pool a shutdown
                                    }
                                }"""),
                        text("conc:composition", """
                                Future.get() waits; CompletableFuture COMPOSES. supplyAsync starts a stage, thenApply transforms its value, thenCompose chains a stage that needs the previous result, exceptionally installs recovery — the failure travels through the pipeline as a value until someone handles it. The pipeline is itself a value you can return, hold, or combine (allOf for fan-out), and independent stages overlap instead of queueing — the difference between a request doing three 100ms calls sequentially and concurrently.

                                Two server-side habits keep it honest. Join at the edge: join() converts async back to sync — right at a controller boundary (or use Spring's async return types, topic 09), wasteful mid-pipeline where it re-blocks a thread you went async to free. And know which pool each stage runs on: with no executor argument every stage lands on the common ForkJoinPool sized to your cores — right for CPU work, WRONG for blocking stages, which starve the pool every parallel stream in the process shares. The exhibit's pipeline, with its explicit recovery arm, is the shape:"""),
                        code("conc:pipeline-code", "java",
                                "guide/domain/teaching/ExhibitConcurrency.java — supplyAsync, thenCompose, exceptionally: a pipeline with recovery",
                                "server/src/main/java/guide/domain/teaching/ExhibitConcurrency.java", """
                                CompletableFuture<Long> first = CompletableFuture.supplyAsync(
                                        () -> failFirstStage ? throwFailure() : (long) input * input);
                                CompletableFuture<Long> result = first
                                        .thenCompose(square -> CompletableFuture.supplyAsync(() -> square + 1))
                                        .exceptionally(error -> {
                                            // recovery: the pipeline still completes — with a fallback
                                            return -1L;
                                        });
                                return result.join();""")),
                section("garbage-collection", "Garbage collection",
                        "Reachability, not references, decides what dies. The generational hypothesis makes collection cheap; G1 and ZGC are two answers to pause time. GC logs are evidence — read them before tuning.",
                        text("gc:reachability", """
                                Concept first: the collector frees what is UNREACHABLE, not what you stopped using. Starting from the roots — every thread's stack, static fields, JNI references — the GC walks the object graph; everything the walk never reaches is garbage, no matter how many "unused" references pointed at it once. This is why a Java "memory leak" looks nothing like a C one: the leak is unintended REACHABILITY — the cache nobody evicts, the listener never deregistered, the static Map that only grows. The object is dead by intent and alive by graph, and no collector can tell the difference.

                                Two consequences. Finalizers are gone from modern practice (deprecated for removal) — cleanup at a determinable time is YOUR job: try-with-resources, topic 01's rule, because GC may close your socket eventually, which is not the same as promptly. And "the GC will handle it" is never a connection strategy: pools guard scarce external resources precisely because GC guards only memory. The collector manages one resource; your process owns many."""),
                        text("gc:generational", """
                                How collection proceeds is the generational hypothesis at work: most objects die young, so the heap is collected UNEVENLY. Minor collections run constantly over the young generation — copying the few survivors aside, reclaiming eden wholesale — and cost microseconds. Old-generation collections run rarely, with mark-sweep-compact machinery over the survivors.

                                The default collector on this JVM is G1 (Garbage First): the heap in thousands of equal regions, young and old assigned dynamically, and a pause-time goal (-XX:MaxGCPauseMillis, 200ms by default) honored by choosing how much of the heap to collect per cycle. For very large heaps with strict pause requirements, ZGC does nearly all its work concurrently with your application — sub-millisecond pauses regardless of heap size — at some throughput cost. Survey-level takeaway: G1's defaults are good, "my pauses are too long and my heap is huge" is the sentence that justifies ZGC; the performance topic turns this into measurements instead of adjectives. What a GC log looks like, so you recognize one in the wild:"""),
                        code("gc:flags", "bash", "GC evidence: the log flag, the live heap, and the container-aware heap settings", null, """
                                # The one flag every JVM service should carry — GC activity as evidence:
                                java -Xlog:gc -jar target/server-0.0.1-SNAPSHOT.jar

                                # …each line is one pause: region counts before/after, pause duration:
                                # [info][gc] GC(42) Pause Young (Normal) 168M->24M(512M) 3.921ms

                                # Heap sizes: initial/max (-Xms/-Xmx), thread stacks (-Xss):
                                java -Xms512m -Xmx512m -Xss1m -jar server.jar

                                # In containers, set heap as a PERCENTAGE of the memory limit, not fixed MBs:
                                java -XX:MaxRAMPercentage=75 -jar server.jar

                                # Against a running process — no restart, no flags:
                                jcmd $(jps -q | head -1) GC.heap_info""")),
                section("pools-vs-virtual-threads", "Thread pools versus virtual threads",
                        "The decision table the whole topic has been building: blocking I/O wants virtual threads, CPU work wants a cores-sized pool — and the synchronized-pinning folklore is stale on this JVM.",
                        text("pool:decision", """
                                Everything above condenses into one decision, made per unit of work. Work that WAITS — HTTP handlers, database calls, file reads, anything socket-shaped — wants virtual threads: they cost bytes, park while blocked, and remove pool sizing from capacity planning; this is why Boot serves MVC requests on virtual threads with one property. Work that COMPUTES — parsing, hashing, image processing — wants a fixed pool sized near the core count: a virtual thread running CPU-bound code never parks, so it is just a thread with extra steps, and a thousand of them on eight carriers buys only scheduler overhead. Mixed pipelines get one executor per work type — a single pool serving both is sized for neither.

                                Two anti-patterns complete the table. Do not run blocking I/O on the common ForkJoinPool (bare supplyAsync, parallel streams): it has exactly core-count threads shared process-wide, and blocking them stalls every parallel operation in the JVM. And do not pool virtual threads — their point is that a thread per task is affordable; if the RESOURCE is scarce (connections, rate limits), bound the resource with a semaphore, not the threads."""),
                        text("pool:pinning", """
                                One caveat from the early-virtual-thread era needs updating, because you will still read it in the wild: "synchronized blocks pin the carrier thread". That was true on JDK 21 through 23 — a virtual thread inside a synchronized block could not unmount its carrier while blocked, so one blocked monitor could strand an OS thread and, worst case, freeze the carrier pool. JDK 24 (JEP 491) rewrote monitor blocking: synchronized and Object.wait now unmount like every other blocking operation, and on this server's Java 25 the caveat is HISTORY — the threads section's synchronized ScenarioStore is virtual-thread-safe by construction, not by luck. What still pins: native (JNI) frames on the stack while blocking — rare in application code. The lesson outlives the fix: the JVM moves fast, blog posts don't — check the JDK version a "virtual threads can't" claim was written against.

                                The platform side of the table is the code the demo has been running all along — the bounded pool, the queue, the waves:"""),
                        code("pool:platform-code", "java",
                                "guide/domain/jvm/ThreadsExhibit.java — the platform run: four bounded OS threads, tasks queueing in waves, shutdown in finally",
                                "server/src/main/java/guide/domain/jvm/ThreadsExhibit.java", """
                                public RunReport runPlatform(int tasks) {
                                    long start = System.nanoTime();
                                    List<String> names = Collections.synchronizedList(new ArrayList<>(tasks));
                                    ExecutorService pool = Executors.newFixedThreadPool(PLATFORM_POOL_SIZE);
                                    try {
                                        List<Future<?>> futures = new ArrayList<>();
                                        for (int index = 0; index < tasks; index++) {
                                            futures.add(pool.submit(() -> names.add(blockAndReport())));
                                        }
                                        awaitAll(futures);
                                    } finally {
                                        shutdown(pool);
                                    }
                                    return report("platform", tasks, start, names);
                                }""")),
                section("terminal-companion", "The JVM demo endpoints from a terminal",
                        "Every topic ends at a terminal: the two demos you just operated, driven by curl.",
                        text("term:tour", """
                                curl is the honest client — no build step, no framework, just what the wire carries. You have met every layer this topic teaches on the wire: the threads endpoints run the platform/virtual comparison with real wall times and real thread names (the carrier shows through in the virtual ones); the streaming endpoint returns the four-consumer report with the split-character damage counted; both validate inputs into the same 400 envelope every endpoint of this app speaks. Actuator's health endpoint — the process answering the deployment topic's orchestrator — closes the tour. Start the server, then take the tour:"""),
                        code("term:curl", "bash", "the curl tour of topic 02", null, """
                                # Both threading models on the same 24 blocking tasks:
                                curl -s 'localhost:8080/api/demo/jvm/threads/compare?tasks=24' | head -60

                                # One model alone — platform, then virtual (watch the names + carrier):
                                curl -s 'localhost:8080/api/demo/jvm/threads?tasks=40&mode=platform' | head -20
                                curl -s 'localhost:8080/api/demo/jvm/threads?tasks=40&mode=virtual' | head -20

                                # The streaming report — Reader clean, naive decode corrupted:
                                curl -s 'localhost:8080/api/demo/jvm/streaming?chunks=16&charset=utf-8'

                                # …and the same payload in UTF-16, boundaries forced mid-character again:
                                curl -s 'localhost:8080/api/demo/jvm/streaming?chunks=16&charset=utf-16'

                                # The 400 envelope for a bad mode (and the valid values, in details):
                                curl -i 'localhost:8080/api/demo/jvm/threads?tasks=8&mode=event-loop'

                                # …and for a charset the exhibit does not teach:
                                curl -i 'localhost:8080/api/demo/jvm/streaming?charset=latin-1'

                                # The process answering the orchestrator's question:
                                curl -s localhost:8080/actuator/health""")));
    }
}
