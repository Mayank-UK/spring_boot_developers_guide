package guide.domain.teaching;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Teaching exhibit for topic 01 (java-language), concurrency section:
 * threads and locks, executors, CompletableFuture, and a taste of virtual
 * threads. It lives in domain/teaching/ because the guide's own server code
 * has not needed these tools yet — its only shared mutable state is
 * ScenarioStore, guarded by synchronized + volatile. When a real executor
 * pipeline lands in the app, that code replaces this exhibit as the quoted
 * source (the documented exemption exists only where no real counterpart
 * exists).
 *
 * Pure JDK — no Spring, no I/O, no sleeps or timing dependence: every method
 * is deterministic, which is what makes it unit-testable.
 */
public final class ExhibitConcurrency {

    private ExhibitConcurrency() {
    }

    /** The immutable alternative to shared mutable state: increment returns a NEW snapshot. */
    public record Counter(long value) {

        public Counter increment() {
            return new Counter(value + 1);
        }
    }

    /**
     * Threads + synchronized: {@code threads} workers each increment a shared
     * slot {@code timesEach} times under one lock. The lock is the lesson —
     * delete the synchronized block and lost updates make the total come back
     * short.
     */
    public static long guardedSum(int threads, int timesEach) throws InterruptedException {
        long[] total = new long[1];
        Object lock = new Object();
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                for (int step = 0; step < timesEach; step++) {
                    synchronized (lock) {
                        total[0] += 1;
                    }
                }
            });
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) {
            worker.join();
        }
        return total[0];
    }

    /**
     * ExecutorService + Future: the same sum on a fixed pool. You never start
     * a thread by hand here — you submit tasks and collect Futures, and the
     * pool owns the threads. Shutdown always runs, in finally: a pool left
     * open keeps its threads (and the JVM) alive after the method returns.
     */
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
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
    }

    /**
     * CompletableFuture: supplyAsync starts the work, thenCompose chains a
     * second stage that needs the first one's result, exceptionally recovers
     * a failed pipeline with a fallback value instead of a throw.
     */
    public static long pipeline(int input, boolean failFirstStage) {
        CompletableFuture<Long> first = CompletableFuture.supplyAsync(
                () -> failFirstStage ? throwFailure() : (long) input * input);
        CompletableFuture<Long> result = first
                .thenCompose(square -> CompletableFuture.supplyAsync(() -> square + 1))
                .exceptionally(error -> {
                    // recovery: the pipeline still completes — with a fallback
                    return -1L;
                });
        return result.join();
    }

    /**
     * Virtual threads (JDK 21+): one cheap carrier-mapped thread per task,
     * and the executor is AutoCloseable since JDK 19 — try-with-resources
     * waits for every task on close. Topic 02 goes deep; this is the taste.
     */
    public static long virtualThreadSum(int tasks) throws Exception {
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < tasks; index++) {
                futures.add(virtual.submit(() -> 1));
            }
            long total = 0;
            for (Future<Integer> future : futures) {
                total += future.get();
            }
            return total;
        }
    }

    private static long throwFailure() {
        throw new IllegalStateException("first stage failed");
    }
}
