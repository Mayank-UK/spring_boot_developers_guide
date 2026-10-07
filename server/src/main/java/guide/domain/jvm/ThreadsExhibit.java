package guide.domain.jvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The live exhibit behind topic 02's jvm:threads demo: the SAME N tiny
 * blocking tasks run twice — once on a small fixed pool of platform threads,
 * once on one virtual thread per task — and every task reports the thread it
 * ran on. The numbers that teach:
 * - platform: distinct thread names are bounded by the pool, and the wall
 *   clock pays ceil(tasks / pool) waves of blocking time
 * - virtual: distinct names == tasks (a thread per task, parked — not
 *   blocked — while it sleeps), and the carrier thread shows through in
 *   toString(): VirtualThread[#…]/runnable@ForkJoinPool-…
 *
 * Deterministic where it matters: counts and name shapes are asserted by
 * tests; wall time is reported but never asserted to an exact value. The
 * sleep simulates blocking I/O — the lesson is what the runtime does while a
 * task waits, not the waiting. Pools never leak: the platform pool shuts
 * down in finally, the virtual executor closes through try-with-resources.
 */
public final class ThreadsExhibit {

    /** Hard ceiling the endpoint accepts; virtual threads make it boring to raise. */
    public static final int MAX_TASKS = 100;
    /** What the demo runs when the caller does not choose. */
    public static final int DEFAULT_TASKS = 24;
    /** The classic small pool: a handful of expensive 1:1-mapped OS threads. */
    public static final int PLATFORM_POOL_SIZE = 4;
    /** Simulated blocking I/O per task — short, uniform, not the lesson. */
    public static final int BLOCK_MILLIS = 10;

    private static final int SHUTDOWN_WAIT_SECONDS = 10;
    private static final int SAMPLE_NAMES = 5;

    /** One run's measurements: wall clock, thread census, name samples, carrier evidence. */
    public record RunReport(String mode, int tasks, long wallTimeMillis, int distinctThreadNames,
            List<String> sampleThreadNames, boolean virtualThreadsObserved, boolean carrierObserved,
            String carrierSample) {

        public RunReport {
            sampleThreadNames = List.copyOf(sampleThreadNames);
        }
    }

    /** Both runs side by side plus the one-line reading of the numbers. */
    public record ComparisonReport(int tasks, RunReport platform, RunReport virtual, String summary) {
    }

    /** N tasks queue on PLATFORM_POOL_SIZE OS threads; the wall clock pays in waves. */
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
    }

    /** N tasks get N virtual threads; each parks while blocked, so all run at once. */
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
    }

    /** Runs both models on the same task count and reads the numbers out loud. */
    public ComparisonReport compare(int tasks) {
        RunReport platform = runPlatform(tasks);
        RunReport virtual = runVirtual(tasks);
        int waves = (tasks + PLATFORM_POOL_SIZE - 1) / PLATFORM_POOL_SIZE;
        String summary = tasks + " blocking tasks: the pool of " + PLATFORM_POOL_SIZE
                + " OS threads ran them in ~" + waves + " waves on " + platform.distinctThreadNames()
                + " threads; the virtual executor ran all " + virtual.distinctThreadNames()
                + " at once, each parked while it slept.";
        return new ComparisonReport(tasks, platform, virtual, summary);
    }

    /**
     * The builder API for one-off async work — no executor needed: build a
     * NAMED virtual thread, start it, join it. Named threads read cleanly in
     * dumps (the default name is VirtualThread[#id]).
     */
    public static String namedVirtualHello() {
        String[] observed = new String[1];
        Thread worker = Thread.ofVirtual().name("guide-worker-", 0).start(() -> {
            observed[0] = Thread.currentThread().toString();
        });
        try {
            worker.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("demo task interrupted", error);
        }
        return observed[0];
    }

    /** The "blocking" task: sleep like I/O, then report the thread that ran it. */
    private static String blockAndReport() {
        try {
            Thread.sleep(BLOCK_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("demo task interrupted", error);
        }
        return Thread.currentThread().toString();
    }

    /** Future.get for every task — a failure inside a task surfaces here, not silently. */
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

    /** A pool left open keeps its threads (and the JVM) alive — always shut down. */
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
    }

    private static RunReport report(String mode, int tasks, long startNanos, List<String> names) {
        Set<String> distinct = new LinkedHashSet<>(names);
        String carrierSample = distinct.stream()
                .filter(name -> name.contains("@ForkJoinPool"))
                .findFirst()
                .orElse(null);
        List<String> sample = distinct.stream().sorted().limit(SAMPLE_NAMES).toList();
        long wallTimeMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new RunReport(mode, tasks, wallTimeMillis, distinct.size(), sample,
                distinct.stream().anyMatch(name -> name.startsWith("VirtualThread")),
                carrierSample != null, carrierSample);
    }
}
