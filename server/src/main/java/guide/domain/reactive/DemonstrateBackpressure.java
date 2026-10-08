package guide.domain.reactive;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Sinks;

/**
 * The reactive:compare demo's second exhibit (topic 41) — backpressure as a
 * PROTOCOL, made deterministic: no timers, no hot loop, one thread. A hot
 * {@link Sinks.Many} source is pushed {@code items} values while its
 * subscriber demands only {@code initialDemand}; the two STRATEGIES answer
 * the overflow differently, and the numbers prove both:
 *
 * - DROP ({@code onBackpressureDrop}): the operator requests unbounded of
 *   the source and DISCARDS whatever arrives without downstream demand —
 *   lossy by design (the scaling topic's load-shedding verdict, expressed
 *   as an operator). After the first drain, a late {@code request(n)} finds
 *   nothing: dropped values never come back.
 * - BUFFER (the unicast sink's own buffer): undelivered values QUEUE in
 *   the sink; the same late {@code request(n)} drains them — nothing lost,
 *   at the price of memory that grows with the gap (bound it in real use
 *   or it is the delayed OutOfMemoryError the scaling topic warned about).
 *
 * The whole choreography is synchronous on the calling thread: subscribe
 * (hookOnSubscribe requests the initial demand) → emit items one by one
 * (each tryEmitNext delivers or drops/buffers immediately) → request the
 * late demand (the sink drains synchronously) → dispose. Counts are exact
 * functions of the parameters — the tests assert every one of them.
 */
public final class DemonstrateBackpressure {

    /** Hard ceilings — a query parameter is hostile until bounded. */
    public static final int MAX_ITEMS = 1024;
    public static final int MAX_DEMAND = 1024;

    /** The measured answer: exact counts for one strategy run. */
    public record BackpressureReport(
            String strategy,
            int emitted,
            int initialDemand,
            int lateDemand,
            int deliveredAfterInitial,
            int deliveredTotal,
            int dropped,
            int stillBuffered,
            List<String> reading) {
    }

    /**
     * Runs one strategy. The controller validates first via
     * {@link #normalizeStrategy(String)}; blank defaults to drop.
     */
    public BackpressureReport run(String strategyParam, int items, int initialDemand, int lateDemand) {
        return "buffer".equals(normalizeStrategy(strategyParam))
                ? buffer(items, initialDemand, lateDemand)
                : drop(items, initialDemand, lateDemand);
    }

    /** null-safe choice normalizer: absent/blank → drop; unknown → null (the caller's 400). */
    public static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "drop";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "buffer".equals(value) || "drop".equals(value) ? value : null;
    }

    /** DROP: a fast source against a slow consumer that sheds the overflow. */
    private BackpressureReport drop(int items, int initialDemand, int lateDemand) {
        Sinks.Many<Integer> source = Sinks.many().unicast().onBackpressureBuffer();
        List<Integer> delivered = new CopyOnWriteArrayList<>();
        AtomicInteger dropped = new AtomicInteger();
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
        };
        source.asFlux()
                .onBackpressureDrop(value -> dropped.incrementAndGet())
                .subscribe(slowConsumer);
        for (int item = 1; item <= items; item++) {
            source.tryEmitNext(item);
        }
        int afterInitial = delivered.size();
        // the late catch-up: drop already shed the surplus — there is nothing left to give
        slowConsumer.request(lateDemand);
        int total = delivered.size();
        slowConsumer.dispose();
        int droppedCount = dropped.get();
        return new BackpressureReport("drop", items, initialDemand, lateDemand,
                afterInitial, total, droppedCount, 0,
                List.of(
                        "hot source emitted " + items + "; the subscriber demanded " + initialDemand
                                + " up front — delivered " + afterInitial + ", DROPPED " + droppedCount + ".",
                        "a late request(" + lateDemand + ") delivered "
                                + (total - afterInitial) + " more: dropped values never return — drop is lossy BY DESIGN.",
                        "this is the scaling topic's load-shedding verdict as an operator: when the consumer"
                                + " cannot keep up, shed fast, keep the newest work, and never queue stale."));
    }

    /** BUFFER: the same slow consumer, but the sink queues the undelivered. */
    private BackpressureReport buffer(int items, int initialDemand, int lateDemand) {
        Sinks.Many<Integer> source = Sinks.many().unicast().onBackpressureBuffer();
        List<Integer> delivered = new CopyOnWriteArrayList<>();
        BaseSubscriber<Integer> slowConsumer = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(initialDemand);
            }

            @Override
            protected void hookOnNext(Integer value) {
                delivered.add(value);
                // no further request yet: the consumer is slow
            }
        };
        source.asFlux().subscribe(slowConsumer);
        for (int item = 1; item <= items; item++) {
            source.tryEmitNext(item);
        }
        int afterInitial = delivered.size();
        int bufferedMid = items - afterInitial;
        // the same late demand — and here it WORKS: the buffered surplus drains on request
        slowConsumer.request(lateDemand);
        int total = delivered.size();
        int stillBuffered = items - total;
        slowConsumer.dispose();
        return new BackpressureReport("buffer", items, initialDemand, lateDemand,
                afterInitial, total, 0, stillBuffered,
                List.of(
                        "hot source emitted " + items + "; the subscriber demanded " + initialDemand
                                + " up front — delivered " + afterInitial + ", BUFFERED " + bufferedMid + " in the sink.",
                        "a late request(" + lateDemand + ") drained " + (total - afterInitial)
                                + " buffered value(s): nothing lost, demand gates the PACE.",
                        "the price is memory: still buffered after the catch-up = " + stillBuffered
                                + ". An unbounded buffer under sustained overload is the delayed OutOfMemoryError"
                                + " the scaling topic warned about — bound it (onBackpressureBuffer(n)) or pair it with a timeout."));
    }
}
