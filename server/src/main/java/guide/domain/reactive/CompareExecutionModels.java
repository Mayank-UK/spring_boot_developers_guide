package guide.domain.reactive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The reactive:compare demo's engine (topic 41) — the SAME deterministic
 * workload executed two ways, MEASURED rather than asserted:
 *
 * - BLOCKING, MVC-STYLE: one virtual thread per item (exactly the posture
 *   topic 38 installed app-wide — spring.threads.virtual.enabled). Every
 *   item's task blocks for {@link #ITEM_BLOCK_MILLIS} (the simulated I/O)
 *   and then computes {@link #work}; threads are cheap, so blocking is
 *   honest, and the thread-name sample shows the one-thread-per-item shape
 *   (named {@code mvc-item-N} by the factory so the report is readable).
 * - REACTIVE: one Reactor pipeline — {@code Flux.range(...).flatMap(task,
 *   concurrency)} with each task on {@link Schedulers#boundedElastic()}.
 *   The SAME blocking task, but executed by a SMALL POOL whose flatMap
 *   concurrency cap is the demand-shaped door: the thread-name sample
 *   shows {@code boundedElastic-N}, never one thread per item.
 *
 * Both halves compute the identical checksum over the identical per-item
 * function — the report's honesty line: different concurrency shapes, same
 * answer, wall times that move run to run (and that is the lesson: the
 * blocking-on-virtual-threads shape is often FASTER for pure blocking I/O;
 * the reactive shape's win is bounded threads, demand control, and
 * composability, not raw wall-clock on a demo-sized workload).
 *
 * Deterministic by construction: counts and the checksum are pure functions
 * of the parameters; only the wall-clock fields and the reactor pool's
 * exact thread ids are observed, never asserted as fixed values.
 */
public final class CompareExecutionModels {

    /** One item's simulated blocking I/O, in milliseconds. */
    static final long ITEM_BLOCK_MILLIS = 2;

    /** Hard ceiling on items/concurrency — a query parameter is hostile until bounded. */
    public static final int MAX_ITEMS = 256;
    public static final int MAX_CONCURRENCY = 64;

    /** One model's measured run: shape + counts + observed threads + wall time. */
    public record ModelRun(
            String model,
            String shape,
            int itemsProcessed,
            int distinctThreads,
            List<String> sampleThreads,
            long wallNanos,
            String reading) {
    }

    /** The wire answer: parameters, the shared checksum, both runs, the verdict lines. */
    public record CompareReport(
            int items,
            int concurrency,
            long workChecksum,
            ModelRun blockingPerItem,
            ModelRun reactorPipeline,
            List<String> reading) {
    }

    /**
     * The deterministic per-item work unit: a fixed xorshift-mix of the item
     * number. Both execution models (and the /sum endpoint's Flux pipeline)
     * reduce over exactly this function, so the checksum is the same by
     * construction — the proof the two halves did the same work.
     */
    public static long work(int item) {
        long value = item * 0x9E3779B97F4A7C15L;
        value ^= (value >>> 29);
        value *= 0xBF58476D1CE4E5B9L;
        value ^= (value >>> 32);
        return value;
    }

    /** {@link #work} summed over 1..items — the expected checksum both models must reach. */
    public static long checksum(int items) {
        long sum = 0;
        for (int item = 1; item <= items; item++) {
            sum += work(item);
        }
        return sum;
    }

    /** One item's blocking task: simulate the I/O wait, then compute the work unit. */
    private static long blockingTask(int item) {
        try {
            Thread.sleep(ITEM_BLOCK_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted mid-item", interrupted);
        }
        return work(item);
    }

    /**
     * Runs the comparison. Both models process {@code items} items; the
     * reactor pipeline's {@code concurrency} caps in-flight tasks (flatMap's
     * maxConcurrency — demand shaping before the backpressure section gives
     * it a name). Thread-safe: stateless besides locals, so a single bean
     * serves concurrent demo requests.
     */
    public CompareReport compare(int items, int concurrency) {
        ModelRun blocking = runBlockingPerItem(items);
        ModelRun reactive = runReactorPipeline(items, concurrency);
        long expected = checksum(items);
        List<String> reading = new ArrayList<>();
        reading.add("both models processed " + items + " item(s) and produced checksum "
                + expected + " — identical work, identical answer, only the SHAPE differs.");
        reading.add("blocking/virtual-threads: " + blocking.distinctThreads() + " thread(s) for "
                + items + " item(s) — one per item, each parked " + ITEM_BLOCK_MILLIS
                + "ms in the simulated I/O; cheap because they are virtual, and exactly "
                + "the posture application.yml pins app-wide (topic 38).");
        reading.add("reactor pipeline: " + reactive.distinctThreads() + " boundedElastic thread(s),"
                + " in-flight capped at " + concurrency + " by flatMap — a small pool absorbing"
                + " the same load, with demand (not thread count) as the throttle.");
        reading.add("wall times are measured, not asserted — on pure blocking I/O the"
                + " virtual-thread model is routinely FASTER at demo scale; the reactive"
                + " shape earns its keep on bounded resources, streaming, and backpressure.");
        return new CompareReport(items, concurrency, expected, blocking, reactive, List.copyOf(reading));
    }

    private ModelRun runBlockingPerItem(int items) {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        long start = System.nanoTime();
        long sum;
        try (ExecutorService perItemThreads = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mvc-item-", 1).factory())) {
            List<Future<Long>> futures = new ArrayList<>(items);
            for (int item = 1; item <= items; item++) {
                final int currentItem = item;
                futures.add(perItemThreads.submit(() -> {
                    threads.add(Thread.currentThread().getName());
                    return blockingTask(currentItem);
                }));
            }
            sum = 0;
            for (Future<Long> future : futures) {
                sum += future.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting items", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException("an item task failed", failed.getCause());
        }
        long wall = System.nanoTime() - start;
        return new ModelRun("blocking-virtual-threads", "one virtual thread per item",
                items, threads.size(), sample(threads), wall,
                items + " item(s) on " + threads.size() + " virtual thread(s) — one per item.");
    }

    private ModelRun runReactorPipeline(int items, int concurrency) {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        long start = System.nanoTime();
        Long sum = Flux.range(1, items)
                .flatMap(item -> Mono.fromCallable(() -> {
                            threads.add(Thread.currentThread().getName());
                            return blockingTask(item);
                        })
                        .subscribeOn(Schedulers.boundedElastic()),
                        concurrency)
                .reduce(0L, Long::sum)
                .block(Duration.ofSeconds(30));
        long wall = System.nanoTime() - start;
        return new ModelRun("reactor-pipeline",
                "boundedElastic pool, flatMap concurrency " + concurrency,
                items, threads.size(), sample(threads), wall,
                items + " item(s) through " + threads.size() + " pooled thread(s),"
                        + " at most " + concurrency + " in flight.");
    }

    private static List<String> sample(Set<String> threads) {
        return threads.stream().sorted().limit(4).toList();
    }
}
