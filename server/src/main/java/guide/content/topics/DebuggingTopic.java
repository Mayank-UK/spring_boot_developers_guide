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
 * Topic 35 — Debugging. Group platform, order 6. The mindset first (narrow
 * the gap, one change at a time, expect before you run), then the cheap
 * tools everyone reaches for and their honest limits (println, breakpoints),
 * then the four instruments that work when it is 2 a.m. and you cannot
 * attach anything: thread dumps — taught live by a deterministic stall you
 * can park and read like a real dump via the JVM's own ThreadMXBean — heap
 * dumps and MAT, sampling profilers (JFR, async-profiler), and the
 * production discipline of debugging the evidence you already shipped.
 */
public final class DebuggingTopic {

    private DebuggingTopic() {
    }

    public static Topic topic() {
        return new Topic("debugging", "Debugging",
                "What to do when it is wrong and you do not know why: a mindset that narrows "
                        + "the gap instead of guessing, the honest limits of println and breakpoints, "
                        + "and the four instruments that work at 2 a.m. — thread dumps (taught by a "
                        + "stall you can park and read), heap dumps, sampling profilers, and the "
                        + "evidence your app already shipped. Bring a bug you have not earned yet.",
                TopicGroupId.PLATFORM, 6,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-debugging-mindset", "The debugging mindset",
                        "A bug is a disagreement between your model and the machine. Debugging is the experiment loop that finds the disagreement — reproduce, narrow, hypothesize, falsify, one change at a time.",
                        text("dm:loop", """
                                A bug is a disagreement between your model of the program and what the program actually does. That framing matters, because it tells you where the bug lives: NOT in the code you distrust — in the gap between what you believe and what is true. You debug by running experiments that shrink the gap, and the loop is always the same five moves. REPRODUCE: a bug you cannot trigger on demand is a bug you cannot observe; getting a deterministic reproduction — the exact request, the exact data, the exact sequence — is half the work, and a flaky reproduction is a diagnosis in itself (state, timing, or ordering). NARROW: find the boundary where behavior diverges from expectation — this input works, that one does not; before this line the value is right, after it it is wrong. Every bug has a frontier, and your job is to walk toward it. HYPOTHESIZE: state, out loud, what you think is wrong — a claim specific enough to be falsified ("the second page of results skips one because offset is computed after the list is mutated", not "the query is buggy"). EXPERIMENT: run the cheapest probe that distinguishes your hypothesis from its alternatives. And then the discipline that separates engineers from thrash: ONE CHANGE AT A TIME. Change two things and the bug disappears — you have learned nothing about which one mattered, and worse, you may have buried a second bug under the first's fix."""),
                        text("dm:honesty", """
                                Three honesty rules that hold up under pressure. First: WRITE DOWN WHAT YOU EXPECT BEFORE YOU RUN. The prediction is the experiment — a probe whose output you did not predict in advance is not testing your model, it is entertaining you. This is also why rubber-ducking works: explaining the code forces the model to make claims, and the claims fail where the bug is. Second: WHEN STUCK, ATTACK THE THING YOU ARE SURE ABOUT. Certainty is where stale models hide; the bug is almost never in the code you have been frowning at for an hour, and almost always in the assumption you have not looked at since Tuesday. Third: KNOW THE EXIT. A debug session ends with a test that FAILED before your fix and PASSES after — that test is the proof the model gap closed, and it is the artifact that makes the fix durable instead of lucky. Without it you did not fix a bug; you changed behavior until the symptom went away.

                                And the mindset's one indulgence: TIMEBOX THE RABBIT HOLES. Ten minutes down a probe that stopped producing new information is the signal to back out to the last known boundary and take the other branch. Debugging is not stubbornness; it is the systematic elimination of everywhere the bug is not.""")),
                section("println-limits", "System.out.println and its limits",
                        "The 30-second tool everyone reaches for first — legitimate for a quick local probe, and wrong the moment it survives past the commit: no level, no context, interleaved under concurrency, and a synchronized write on the hot path.",
                        text("so:trap", """
                                Every developer's first debugger is `System.out.println("here? " + x)` — and it is a legitimately GOOD tool for its actual job: a 30-second local probe you write, run, and DELETE. The trap is everything it becomes when it survives past the commit. A println has no LEVEL — you cannot turn it down in production without a redeploy, so every line you ship is a line you will read forever (or worse, grep past) in every environment. It has no CONTEXT — no timestamp beyond the container's line prefix, no request id, no trace id, no logger name — so under concurrent load the lines from forty requests INTERLEAVE into prose soup, and "here 1 / here 2 / here 2 / here 1" tells you nothing about which 2 followed which 1. It pays the string-concatenation cost UNCONDITIONALLY — `"user=" + user + " cart=" + cart` builds the string even when nobody is reading stdout, and a toString() that walks a lazy association can even hit your database as a side effect of "just logging". And mechanically, System.out is one shared synchronized stream: a println in a hot loop does not just print, it SERIALIZES the threads printing through one lock — logging that becomes its own contention bug.

                                The structured line this app already writes per request is the counterexample: what println would spray, it says ONCE, with fields a machine can join — method, path, status, duration, at a level that means something, with the request id riding in MDC where every appender finds it:"""),
                        code("so:filter", "java",
                                "guide/presentation/filter/RequestLoggingFilter.java — one completion line per request: the discipline println never had",
                                "server/src/main/java/guide/presentation/filter/RequestLoggingFilter.java", """
                                public final class RequestLoggingFilter extends OncePerRequestFilter {

                                    private static final Logger LOG = LoggerFactory.getLogger("guide.http");

                                    @Override
                                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                                        long startNs = System.nanoTime();
                                        try {
                                            filterChain.doFilter(request, response);
                                        } finally {
                                            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                                            // … method, path and status are pulled from the request and response
                                            if (path.startsWith("/api/")) {
                                                logCompletion(method, path, status, durationMs, response);
                                            }
                                        }
                                    }

                                    /** The level-by-status contract: 2xx/3xx info, 4xx warn, 5xx error. */
                                    static Level levelFor(int status) {
                                        if (status >= 500) {
                                            return Level.ERROR;
                                        }
                                        if (status >= 400) {
                                            return Level.WARN;
                                        }
                                        return Level.INFO;
                                    }
                                }"""),
                        text("so:verdict", """
                                The verdict, honestly: println debugging is not a sin, it is a TOOL WITH AN EXIT. Used inside one local experiment — write the probe, run it, read it, delete it — it is often the fastest way to test a hypothesis. Used as the debugging strategy — lines accreting across a codebase, gated by nothing, readable by no machine — it is the habit this app's whole logging stack exists to replace. The same paragraph, one section later, applies almost verbatim to its richer cousin: the breakpoint.""")),
                section("breakpoints-intellij-and-jdb", "Breakpoints: IntelliJ, jdb, and remote debug",
                        "The debugger's three superpowers — stop, inspect, evaluate — and its one hard rule: never on a production process. A hit breakpoint parks a thread; on a server, parked threads are an outage.",
                        text("bp:model", """
                                A breakpoint is not magic: when you set one, the JVM instruments that location so that when execution arrives, the thread STOPS — parked at a safepoint — and the debugger (speaking JDWP, the Java Debug Wire Protocol) takes over: you inspect locals and fields, walk the stack, and resume when done. From that primitive come the three superpowers. STOP anywhere, on a condition — a CONDITIONAL breakpoint (`user.id == 4242`) is the difference between pressing Continue ten thousand times and having the debugger break on exactly the order you care about. INSPECT everything — no more guessing which of six suspects is null; you can SEE the whole object graph at the moment of the crime, including things you never thought to print. EVALUATE arbitrary expressions against that frozen moment — call a method, run a query shape, test what WOULD happen, without editing or recompiling anything. Exception breakpoints earn their own mention: break WHERE the exception is thrown, not where it is caught and logged — the stack at the throw site still has the causal frames that every catch-and-log site has already destroyed. And IDEA's "Evaluate and Log" plus conditional breakpoints give you the watch-window-without-stopping workflow when stopping is too disruptive.

                                The same machinery works from a bare shell when there is no IDE: jdb speaks the same protocol. Open the JDWP port on the JVM (the flag belongs in your local run config, never in a shared environment), attach, and you have breakpoints, stacks, and locals in a terminal:"""),
                        code("bp:jdb", "bash",
                                "JDWP and jdb: the debugger protocol over a socket, no IDE required",
                                null, """
                                # local-only, never in a shared environment: open the debug port on the JVM
                                java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar

                                jdb -attach 5005
                                > stop at guide.data.debug.InProcessStallTheater:127   # break where the victim parks
                                > threads                                            # every thread, its state
                                > where 12                                           # one thread's stack, by id
                                > print stopRequested                                # inspect a field, frozen
                                > cont"""),
                        text("bp:rules", """
                                Breakpoint hygiene, learned the expensive way. Watch the SIDE EFFECTS of inspection: IDEA calls toString() to render an object in the debugger — if that toString() is custom and lazy, merely LOOKING at a variable can load an association, mutate a counter, or take seconds; and an "Evaluate expression" that calls a mutating method has now changed the state of the very bug you were inspecting (the debugger's version of the observer effect). Prefer STEP-OVER discipline over breakpoint-spraying: two or three well-placed breakpoints at the boundaries of the suspect region beat twenty and a lot of F8. Do not leave breakpoints in RUN CONFIGURATIONS other people inherit — the teammate whose local boot hangs for ninety seconds on your conditional breakpoint will not thank you. And when stepping in a framework-heavy stack, learn "step into MY code" filters; stepping into Spring's reflection soup is how an afternoon disappears.

                                The one HARD rule, and the reason this section sits in a server-side guide: NO BREAKPOINTS ON A SHARED OR PRODUCTION PROCESS. A hit breakpoint parks the thread that reached it. A servlet container has a FINITE worker pool (200 by default in Tomcat) — a breakpoint that a hundred requests an hour trip parks a hundred worker-threads an hour, and long before the pool empties you have converted a debugging session into a self-inflicted denial of service against every user who shares that process. suspend=n in the JDWP flags does not save you: the flag only means the JVM starts without waiting for the debugger to attach; the THREAD still stops at every hit. In production, the breakpoint is exactly the println from the last section — with a stop-the-world cost.""")),
                section("thread-dumps", "Thread dumps: reading a frozen moment",
                        "Every live thread, its state, its stack, and the locks it holds or waits for — a census of one instant, free and safe to take. Park a deterministic stall here and read it like a real dump.",
                        text("td:what", """
                                A thread dump is a census: every live thread, its NAME, its STATE, its stack top-down, and the monitors it holds or waits for — all captured at one instant. It is the first instrument to reach for on a server precisely because it is CHEAP (no agent, no restart, no flag you forgot to set) and COMPLETELY SAFE — taking a dump pauses nothing; it reads the state the JVM already tracks. The five states you will actually meet: RUNNABLE (running or ready to — the thread thinks it is working; whether the CPU agrees is a separate question), BLOCKED (waiting to ENTER a synchronized block someone else holds — the dump names both the monitor and its OWNER), WAITING (parked indefinitely — Object.wait, LockSupport.park, a queue take; it wakes only when someone does something), TIMED_WAITING (the same, with an alarm clock — sleep, wait-with-timeout, poll-with-timeout), and NEW/TERMINATED (the edges you rarely see). One state caveat that saves an hour: RUNNABLE does not mean "making progress" — a thread busy-waiting on a condition, or spinning in a regex catastrophic backtracker, is RUNNABLE while going nowhere, and a thread doing socket I/O is RUNNABLE even while the OS has it parked on the network (the JVM models socket reads as runnable since the thread is runnable-in-principle the moment data arrives).

                                The demo below makes those words concrete and REPEATABLE. It parks two named threads in the classic story — an owner that falls asleep while HOLDING a monitor (Thread.sleep releases nothing), and a victim that tries to enter the same synchronized block and parks at the door, BLOCKED, with the dump naming the holder — plus a second shape, a spinner that is RUNNABLE forever. The engine guarantees the teaching states before it answers: the owner signals a latch from INSIDE the block (proof the monitor is held), and start() waits for the victim to actually reach BLOCKED before returning. Deterministic, on purpose:"""),
                        code("td:theater", "java",
                                "guide/data/debug/InProcessStallTheater.java — the owner/victim story: sleep holds the monitor, the victim parks at the door, deterministically",
                                "server/src/main/java/guide/data/debug/InProcessStallTheater.java", """
                                private void startLockStall() {
                                    CountDownLatch monitorHeld = new CountDownLatch(1);
                                    owner = new Thread(() -> {
                                        synchronized (monitor) {
                                            monitorHeld.countDown();
                                            while (!stopRequested) {
                                                try {
                                                    // asleep, but STILL HOLDING the monitor — sleep releases nothing
                                                    Thread.sleep(200);
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    return;
                                                }
                                            }
                                        }
                                    }, OWNER_NAME);
                                    owner.setDaemon(true);
                                    owner.start();
                                    await(monitorHeld, "the owner never took the monitor");
                                    victim = new Thread(() -> {
                                        // reached only AFTER the owner released the monitor; until then BLOCKED at entry
                                        synchronized (monitor) {
                                            // … the victim's block only ever runs during shutdown
                                        }
                                    }, VICTIM_NAME);
                                    victim.setDaemon(true);
                                    victim.start();
                                    awaitState(victim, Thread.State.BLOCKED, "the victim never reached BLOCKED");
                                }"""),
                        code("td:spin", "java",
                                "guide/data/debug/InProcessStallTheater.java — the other smell: RUNNABLE forever, burning a core while going nowhere",
                                "server/src/main/java/guide/data/debug/InProcessStallTheater.java", """
                                private void startSpinStall() {
                                    spinner = new Thread(() -> {
                                        while (!stopRequested) {
                                            // a volatile write the JIT cannot elide: honest busy-work, RUNNABLE forever
                                            spinSink = spinSink * 31 + 1;
                                        }
                                    }, SPINNER_NAME);
                                    spinner.setDaemon(true);
                                    spinner.start();
                                    awaitState(spinner, Thread.State.RUNNABLE, "the spinner never reached RUNNABLE");
                                }"""),
                        demo("td:stall-demo", "debug:stall", "Park a stall, read it like a dump",
                                "Park the lock contention (owner asleep holding the monitor, victim BLOCKED at the door — the dump names the holder) or the busy spin (RUNNABLE forever). The snapshot panel renders exactly what jstack would print — names, states, locks with holders, stacks — plus the reading lines that say what each state means. Stop wakes and joins everything: repeatable, nothing leaked."),
                        text("td:diagnostics", """
                                What you are reading in that panel is not an imitation of a dump — it IS one, taken from inside the process. The JVM exposes the same machinery jstack reads over the wire as an MXBean, and the demo's diagnostics class asks it directly: one ThreadInfo per thread, carrying the name, the state, the lock it wants with the OWNER's name, and the stack with a frame cap so the wire stays bounded. This is the exact seam every "thread dump" tool uses — jstack, jcmd Thread.print, the actuator's threaddump endpoint, and IntelliJ's "Get Thread Dump" button all read the same data through the same interface:"""),
                        code("td:diagnostics-code", "java",
                                "guide/domain/debug/ThreadDiagnostics.java — the dump from inside: ThreadMXBean hands out exactly what jstack prints",
                                "server/src/main/java/guide/domain/debug/ThreadDiagnostics.java", """
                                /** One thread as a dump would show it; null when the thread has already died. */
                                public StallThreadView snapshotOf(Thread thread) {
                                    ThreadInfo info = threads.getThreadInfo(thread.getId(), MAX_STACK_DEPTH);
                                    if (info == null) {
                                        return null; // died between listing and asking — report nothing, honestly
                                    }
                                    String[] frames = new String[info.getStackTrace().length];
                                    for (int i = 0; i < info.getStackTrace().length; i++) {
                                        StackTraceElement element = info.getStackTrace()[i];
                                        frames[i] = "at " + element.getClassName() + "." + element.getMethodName()
                                                + "(" + element.getFileName() + ":" + element.getLineNumber() + ")";
                                    }
                                    return new StallThreadView(info.getThreadName(), info.getThreadState().toString(),
                                            info.getLockName(), info.getLockOwnerName(), List.of(frames));
                                }"""),
                        text("td:practice", """
                                The practice loop that turns dumps into a diagnosis: take THREE, a few seconds apart, and compare. A thread that shows the SAME stack in every sample is where that thread's life is going — which makes a handful of dumps the POOR MAN'S PROFILER (the sampling-profilers section is this same idea, industrialized: interrupt on a timer, aggregate the samples). A thread pool whose workers are all BLOCKED on the same monitor points at the owner named in their lines — find that owner's stack and you have your culprit, exactly the owner/victim shape the demo parks. Many threads WAITING on a connection pool is a different story: saturation, not a bug — the fix is capacity or leakage, not a lock. And the mxbean can answer one question a static dump cannot: `findDeadlockedThreads()` reports cycles (A holds what B wants while B holds what A wants) — the one stall no amount of waiting resolves.

                                The boundary discipline for the demo's own API matches the guide's every other edge — unknown kinds are typed validation failures, and starting while a stall is parked is a typed conflict, so the tour is honest even when you click twice:"""),
                        code("td:boundary", "java",
                                "guide/domain/debug/StartStall.java — the demo's own boundary: kinds validated, double-start a typed conflict",
                                "server/src/main/java/guide/domain/debug/StartStall.java", """
                                public Result<StallReport> execute(String kind) {
                                    String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
                                    if (!KINDS.contains(normalized)) {
                                        return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                "unknown stall kind \\"" + normalized + "\\" — the two are " + String.join(", ", KINDS),
                                                java.util.Map.of("field", "kind")));
                                    }
                                    try {
                                        theater.start(normalized);
                                    } catch (IllegalStateException failed) {
                                        if (theater.isRunning()) {
                                            return Result.failed(Failure.of(FailureKind.CONFLICT,
                                                    "a stall is already parked (" + theater.kind() + ") — stop it first", null));
                                        }
                                        // … the never-settled case answers an honest internal error instead
                                    }
                                    return Result.success(read.execute());
                                }"""),
                        code("td:ts", "ts",
                                "client/src/ui/demos/debugging/debug_stall_demo.tsx — park, snapshot, stop: the theater as a live surface",
                                "client/src/ui/demos/debugging/debug_stall_demo.tsx", """
                                const start = (kind: 'lock' | 'spin') =>
                                  void safeRepositoryCall(async () =>
                                    parseReport(await requestJson(config, `/demo/debug/stall/start?kind=${kind}`, { method: 'POST' })),
                                  ).then((result) => {
                                    if (result.ok) {
                                      setReport(result.value);
                                      setPanel({ kind: 'idle' });
                                    } else {
                                      // a typed 409 (already parked) or 400 (unknown kind) stays visible
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });"""),
                        text("td:jstack-note", """
                                For reference, what the same parked stall looks like through the real tool — `jcmd <pid> Thread.print` against a live park of this demo (abridged; ids differ every run). Every fact the panel rendered is here in jstack's own dialect: the owner TIMED_WAITING (sleeping) while holding the monitor, the victim BLOCKED (waiting for monitor entry) with the waiting-to-lock line naming the SAME monitor id the owner's locked line carries — that id match is how you close the loop from victim to culprit in a real dump:"""),
                        code("td:jstack", "bash",
                                "the parked stall as jcmd prints it — abridged to the two lines that matter",
                                null, """
                                $ jcmd <pid> Thread.print | grep -A4 'debug-stall-'
                                "debug-stall-owner" #57 daemon prio=5 tid=0x… nid=… waiting on condition
                                   java.lang.Thread.State: TIMED_WAITING (sleeping)
                                     at java.lang.Thread.sleep(java.base/Native Method)
                                     at guide.data.debug.InProcessStallTheater.lambda$startLockStall$0(…)
                                     - locked <0x00007f8b2c001234> (a java.lang.Object)      ← the owner HOLDS it
                                "debug-stall-victim" #58 daemon prio=5 tid=0x… nid=… waiting for monitor entry
                                   java.lang.Thread.State: BLOCKED (on object monitor)
                                     at guide.data.debug.InProcessStallTheater.lambda$startLockStall$1(…)
                                     - waiting to lock <0x00007f8b2c001234> (a java.lang.Object) ← SAME id: case closed""")),
                section("heap-dumps", "Heap dumps and MAT: what is actually in memory",
                        "A census of the heap — every object, every reference. The triage order that keeps it cheap: histogram first, dominator tree second, full dump only when the story needs it.",
                        text("hd:what", """
                                If a thread dump asks "what is every thread doing", a heap dump asks "what is every OBJECT doing here". The .hprof file the JVM writes is a census of the heap: every live object, its class and fields, and enough of the reference graph to answer the only question that matters when memory grows — WHAT IS KEEPING THIS ALIVE? The tooling is one command (`jcmd <pid> GC.heap_dump /tmp/app.hprof`, or jmap -dump:live for the older dialect) and the reader is Eclipse MAT (Memory Analyzer Tool), purpose-built for exactly these files.

                                But go in the right order, because each step costs more than the last. The CHEAPEST triage is the class histogram — `jcmd <pid> GC.class_histogram` — one line per class with instance counts and shallow bytes, no dump file, seconds to run, safe on a loaded process. It answers "WHAT is big": a million instances of some DTO, byte[] towers, 40,000 Connection objects — often enough to recognize the story on the spot. The histogram's limit is also its honesty: it shows what the objects ARE, never why they are still there. For the why you need the dump and MAT's dominator tree — the heap arranged so each object sits under the object that KEEPS IT ALIVE, with each node's RETAINED size (everything that would be freed if that node were cut). "Who retains the most" is the leak question answered directly, and MAT's Leak Suspects report automates the first pass: it names the biggest dominator chains and labels them in plain language. The last mile is PATH TO GC ROOTS on a suspect: right-click, and MAT draws the exact reference chain from the object up to a GC root — a static field, a thread, a classloader — the chain along which liveness flows. That chain is the fix: break ONE link (the map entry, the listener registration, the ThreadLocal set by a pooled thread) and the whole subtree becomes garbage.

                                The costs, stated plainly: a full heap dump STOPS THE WORLD while it writes (a pause on the order of the heap's size — gigabytes mean seconds-to-minutes) and lands a file roughly as big as the heap, on a disk you chose. That is why production prefers the OOM-triggered dump — `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps` — paid only at the moment it is needed most, when the process is dying anyway and the dump IS the autopsy; and why you snapshot ONE instance, not all five."""),
                        code("hd:commands", "bash",
                                "the memory triage ladder: histogram first, dump second",
                                null, """
                                jcmd <pid> GC.class_histogram | head -12    # 2 seconds, no file: WHAT is big
                                #   num       #instances         #bytes  class name (module)
                                #   1:        3,412,009     1,063,411,584  [B  ([java.base@25])
                                #   2:        1,281,556       476,192,912  guide.data.uploads.ChunkHolder

                                jcmd <pid> GC.heap_dump /dumps/app.hprof   # stops the world, disk = heap size
                                # then in Eclipse MAT: Leak Suspects → Dominator Tree → Path to GC Roots"""),
                        text("hd:patterns", """
                                The recurring casts, so you can recognize them on sight. The UNBOUNDED CACHE: a map that only ever puts, "it's just metadata, tiny" — retaining objects whose own retained sets are the entire leak. The FORGOTTEN LISTENER: an event bus where subscribe registered a reference and nothing ever unregisters — each short-lived subscriber retained forever by one long-lived registry. The POOLED THREADLOCAL: a ThreadLocal set on a Tomcat worker thread, cleared by nobody — the worker lives for days, and the value rides it across a thousand unrelated requests (this is THE classic Spring-Security/MDC-adjacent leak; clear your ThreadLocals in a finally, the request-id filter of this app being the pattern done right). The BIG BATCH: not a leak at all, just a design that materializes a month of rows to compute one number — MAT shows one gigantic list retained by one controller frame, and the fix is the query, not the memory. And the FRAMEWORK SURPRISE: one level of entity associations eagerly fetched, each row pulling its whole neighborhood — the JPA topic's N+1, seen from the heap's side. A dump does not fix any of these; it NAMES them, which is the part your intuition keeps getting wrong.""")),
                section("sampling-profilers", "JFR and async-profiler: sampling instead of stopping",
                        "Where does the time actually go? Sampling answers with a few percent of error and near-zero overhead — two tools (JFR inside the JVM, async-profiler beside it) and the discipline of reading what they actually measured.",
                        text("pf:model", """
                                "It is slow" is not a diagnosis; "70% of samples sit inside buildReport" is. The honest way to get from one to the other is SAMPLING: every few milliseconds, ask the thread to tell you its stack (a safepoint-biased interrupt in the simplest case), write the sample down, and after thirty seconds AGGREGATE — the stacks that appear most often are where the time went, with the sampling-profiler's standing error bar of a few percent. That error is fine: you are not looking for the truth, you are looking for the BIG rocks, and big rocks survive a 5% error. The alternative — INSTRUMENTATION, wrapping every method with a timing call — measures precisely what it also CHANGES (each wrapper costs, and wrappers on hot paths distort the very thing you measured), which is why production profiling is sampling and instrumentation is for the spans you deliberately chose (the observability topic's timers).

                                Two samplers matter on the JVM. JFR — Java Flight Recorder — is built into the JVM and always one jcmd away: it records a stream of structured EVENTS (execution samples for CPU, allocation samples via TLAB handouts, lock contention, GC pauses, method timings) at a overhead budget you cap with a settings profile (the `profile` preset leans in, `default` stays near-invisible). The recording is a compact .jfr file you read in JDK Mission Control or the `jfr` CLI — and its killer property is that it is safe to leave RUNNING: continuous low-settings flight recording in every production process is a legitimate, common posture, because "the last 10 minutes" of evidence from BEFORE the incident is the evidence you never get from start-on-demand. The one-liner tour: start a 60-second profile recording, dump it, print the hottest stacks. async-profiler (asprof) is the second tool: it samples via perf interrupts on the OS side, which sidesteps the JVM safepoint bias that can over-represent interpreter/stub frames and under-represent tight loops — and it renders FLAMEGRAPHS natively (`-f /tmp/flame.html`), the visualization where width-is-time makes "where does it go" a picture you point at. CPU, ALLOC, LOCK, WALL are its event dials — the same dials JFR exposes as event types. Reach for it when the story is native frames, tight numeric loops, or when you want the flame graph in one command."""),
                        code("pf:commands", "bash",
                                "the two samplers, one tour each: JFR on demand, async-profiler as a flame graph",
                                null, """
                                # JFR: 60s profile recording, then read it back with the bundled CLI
                                jcmd <pid> JFR.start name=slow-report settings=profile duration=60s filename=/tmp/tour.jfr
                                jcmd <pid> JFR.check                       # what is recording, for how long
                                jfr print --events jdk.ExecutionSample /tmp/tour.jfr | grep 'at ' | sort | uniq -c | sort -rn | head
                                # in JDK Mission Control instead: open the .jfr → Method Profiling / Garbage Collection

                                # async-profiler: 30s of CPU samples straight into a flame graph
                                asprof -e cpu -d 30 -f /tmp/flame.html <pid>
                                asprof -e alloc -d 30 -f /tmp/alloc.html <pid>   # the same picture for allocations"""),
                        text("pf:read", """
                                Reading the output without fooling yourself. SELF versus TOTAL: a method high in total time but flat in self time is a CORRIDOR — the time is spent in what it calls, and optimizing the corridor does nothing; a method high in self time is the WORK, and that is where you look line by line. The top-of-stack rule: the frame at the TOP of each sample is where the sample was taken — profiles are read as "where do samples LAND", not "what called what". GC in the profile is not noise: a third of your samples inside collection pauses is a memory problem wearing a CPU costume, and the allocation profile (which objects allocate the most) is usually the lever. And the two classic self-owns: profiling the WRONG WORKLOAD (a 30-second sample taken while no traffic runs measures your idle loop, not your product) and profiling after JIT warmup distortion (the first seconds measure the interpreter — let the process get warm, or discard the head of the recording). The performance topic picks this discipline up in full; here the point is smaller and prior: MEASURE BEFORE YOU OPTIMIZE, because your guess about where time goes is a model too — and this whole topic is about not trusting models you have not tested.""")),
                section("debugging-in-production", "Debugging in production: the evidence you already shipped",
                        "You cannot attach a debugger and you should not redeploy on a hunch. Read what the system already recorded — the three questions' machinery — then reach for live tools (threaddump on demand, one heap dump) with intent.",
                        text("pr:no-attach", """
                                Production debugging starts from a hard constraint: you cannot BREAKPOINT the process (last section's self-DoS), you cannot println-and-redeploy on a hunch (a redeploy DESTROYS the evidence — heap state, thread state, that unreproducible moment — and resets every counter you were watching), and you cannot experiment freely on the traffic that is paying you. So the loop inverts: first read everything the system ALREADY recorded, and only then reach for the live instruments, one deliberate action at a time. The recorded evidence is the observability topic's three questions wearing incident-response clothes: the COMPLETION LINES and the logs behind them ("is it right" — errors with request ids you can join to a user's report), the METERS and their percentiles ("is it fast" — and WHICH endpoint, since when), the PROBES and the process-level nets ("is it up" — liveness, restarts, the uncaught-exception log with its thread name). Half of real production debugging is grepping what you shipped before the incident, well, and that half is the reason ids-join-everything was built the way it was.

                                The live instruments you DO have, ranked by cost. Thread dumps are free and repeatable — grab three, seconds apart, from `jcmd <pid> Thread.print` inside the pod (kubectl exec) or the host. The class histogram is nearly free. A heap dump is the expensive one: it stops the world and takes a disk-sized file — take it ONCE, from ONE instance, when the memory story cannot be told without it, and prefer the OOM-triggered dump that the dying process writes for you. JFR you may already HAVE if continuous recording was left on — check `JFR.check` before you start anything new; the recording that began before the incident is worth more than any you start after. And one surface decision to make BEFORE the incident, not during: the actuator can serve all of this over HTTP — threaddump and even heapdump endpoints exist — but each is a one-line yaml decision about EXPOSING internals. This app's own exposure, and the deliberate line it draws:"""),
                        code("pr:yaml", "yaml",
                                "server/src/main/resources/application.yml — the actuator surface this app ships: metrics and health, deliberately NOT the dump endpoints",
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
                        text("pr:surface", """
                                Read that exposure list as a decision, because it is one. `threaddump` and `heapdump` are actuator endpoints that exist and are one include-line away — and both belong behind the same deliberation as everything else that answers over HTTP: a thread dump names your classes and their stacks; a HEAP DUMP is a full copy of process memory INCLUDING secrets, tokens, decrypted payloads and personal data (the security topic's PII rules apply to a heap dump with interest — it is a data-exposure artifact, treat its file like a credential). The standard choices, pick one with intent: leave the endpoints OFF and pull dumps with jcmd from inside the container (this app's default posture — nothing exposed, everything still one exec away); expose them on a SEPARATE management port bound where only the platform can reach it; or expose them on the main port and own the authentication story. What you may not do is inherit the default in silence — "the surface grows only when a topic teaches it" is the yaml's own comment, and this topic is where that decision gets taught.

                                Then the incident loop itself, compact enough to hold at 3 a.m.: WHAT CHANGED (deploy, config, traffic shape, data shape — the four usual suspects, in that order). WHAT DOES THE EVIDENCE SAY (errors with ids joined, the meters' percentiles, the probes' verdicts — the three questions, answered from what shipped). ONE LIVE PROBE AT A TIME (dumps first, histogram next, heap dump once, JFR if it was already rolling), each chosen to falsify the CURRENT hypothesis, not to "gather more data". And when you find it: the same exit as local debugging — a fix with a test that failed before it, and a note in the incident record of what evidence actually located the bug, so the NEXT 3 a.m. starts from a playbook instead of a blank page. Restarting, for the record, remains what the observability topic said: a stalling tactic that destroys evidence, right exactly when the process state itself is the problem — and even then, take the dumps FIRST.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic, curled: park the stall, read it like a dump, stop it clean — then the same moves with the real tools against the real process.",
                        text("tc:intro", """
                                Every screen in this topic has a terminal twin. The tour assumes the server on localhost:8080. The demo endpoints are parked behind the same thin controller as every other demo — start (park, deterministically), threads (the snapshot), stop (wake and join):"""),
                        code("tc:controller", "java",
                                "guide/presentation/demo/DebugDemoController.java — the three endpoints this tour curls, thin as always",
                                "server/src/main/java/guide/presentation/demo/DebugDemoController.java", """
                                @PostMapping(value = "/api/demo/debug/stall/start", produces = MediaType.APPLICATION_JSON_VALUE)
                                StallReport start(@RequestParam(defaultValue = "lock") String kind) {
                                    return unwrap(startStall.execute(kind));
                                }

                                @PostMapping(value = "/api/demo/debug/stall/stop", produces = MediaType.APPLICATION_JSON_VALUE)
                                StallReport stop() {
                                    return unwrap(stopStall.execute());
                                }

                                @GetMapping(value = "/api/demo/debug/stall/threads", produces = MediaType.APPLICATION_JSON_VALUE)
                                StallReport threads() {
                                    return readStallThreads.execute();
                                }"""),
                        text("tc:park", """
                                Park the lock story and read it — the owner TIMED_WAITING while holding the monitor, the victim BLOCKED naming its holder, the reading lines narrating both. Then the typed failures worth seeing once each: a second start answers 409 conflict, an unknown kind 400 validation_failed — the demo practices the boundary discipline it preaches:"""),
                        code("tc:curl-stall", "bash",
                                "the stall theater from a terminal",
                                null, """
                                curl -s -X POST 'localhost:8080/api/demo/debug/stall/start?kind=lock' | head -c 400
                                # {"running":true,"kind":"lock","startedAtMs":1760004210961,"lastParkedMs":0,
                                #  "jvmThreadCount":47,"threads":[
                                #   {"name":"debug-stall-owner","state":"TIMED_WAITING","lock":null,"lockOwner":null,
                                #    "stack":["at java.lang.Thread.sleep(…)"]},
                                #   {"name":"debug-stall-victim","state":"BLOCKED","lock":"java.lang.Object@2de96eba",
                                #    "lockOwner":"debug-stall-owner","stack":["at guide.data.debug.…"]}],
                                #  "reading":["debug-stall-owner is TIMED_WAITING — …", …]}

                                curl -s localhost:8080/api/demo/debug/stall/threads | python3 -m json.tool | head -20

                                curl -s -X POST 'localhost:8080/api/demo/debug/stall/start?kind=spin' | head -c 120
                                # {"error":{"code":"conflict","message":"a stall is already parked (lock) — stop it first", …}}

                                curl -s -X POST 'localhost:8080/api/demo/debug/stall/start?kind=deadlock' | head -c 120
                                # {"error":{"code":"validation_failed","field":"kind", …}}

                                curl -s -X POST localhost:8080/api/demo/debug/stall/stop | head -c 200
                                # {"running":false,"threads":[],"lastParkedMs":8413,
                                #  "reading":["no stall is parked — the previous one ran for 8413ms; …"]}"""),
                        text("tc:tools", """
                                Then the same moves on the REAL process — the tools this topic exists for, against the server you just parked (or any JVM). Find the pid, take three dumps seconds apart, and compare the stacks that refuse to move:"""),
                        code("tc:tools-curl", "bash",
                                "the real instruments, against the real process",
                                null, """
                                jps -l | grep server            # → <pid> is what every jcmd below wants
                                jcmd <pid> Thread.print > /tmp/dump1.txt && sleep 3 \\
                                  && jcmd <pid> Thread.print > /tmp/dump2.txt && sleep 3 \\
                                  && jcmd <pid> Thread.print > /tmp/dump3.txt
                                grep -c 'debug-stall-' /tmp/dump1.txt      # the parked pair, by name
                                diff /tmp/dump1.txt /tmp/dump3.txt | head  # a real stall does not move

                                jcmd <pid> GC.class_histogram | head -12   # memory triage, 2 seconds
                                jcmd <pid> JFR.start name=tour settings=profile duration=60s filename=/tmp/tour.jfr
                                jfr print --events jdk.ExecutionSample /tmp/tour.jfr | grep 'at ' | sort | uniq -c | sort -rn | head""")));
    }
}
