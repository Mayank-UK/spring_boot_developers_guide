package guide.domain.realtime;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * The SSE demo's engine (topic 28): a price-ish tick generator over a fixed
 * set of symbols, built around one load-bearing decision — THE FEED PAUSES
 * WHEN NOBODY LISTENS. {@link #tick(Instant)} is the only place ticks are
 * born, and its first line returns when the listener list is empty: no
 * prices move, no ring grows, no counter advances. A push service with zero
 * subscribers should cost zero.
 *
 * The seams follow the webhooks engine's pattern: time arrives as a
 * PARAMETER (the pump adapter turns wall-clock beats into tick(now) calls;
 * tests call it with constructed instants), and the random walk is an
 * injected {@link DoubleSupplier} (production wires a Random; tests wire a
 * constant and predict the drift). Delivery is through plain consumers, so
 * the controller owns everything socket-shaped — this class never sees an
 * SseEmitter.
 *
 * The ring buffer is the Last-Event-ID replay window: bounded to
 * {@link #RING_CAPACITY} entries, honest about what it no longer holds
 * (a resume older than the window replays what survived, never fails, never
 * blocks).
 */
public final class TickerFeed {

    /** One generated market tick: sequence id (the SSE event id), symbol, price, drift, instant. */
    public record Tick(long seq, String symbol, double price, double delta, Instant at) {
    }

    /** The replay window: 64 ticks per symbol-set at the demo pace is ~10s of history. */
    static final int RING_CAPACITY = 64;

    /** How far one step can move a price: ±0.2% — visible, never violent. */
    private static final double MAX_DRIFT_FRACTION = 0.004;

    private final DoubleSupplier noise;
    private final List<String> symbols;
    private final Map<String, Double> prices;
    private final ArrayDeque<Tick> ring = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Consumer<Tick>> listeners = new CopyOnWriteArrayList<>();
    private int cursor;
    private long seq;
    private long ticksGenerated;

    public TickerFeed(DoubleSupplier noise) {
        this(noise, List.of("SDG", "JDK", "PG"));
    }

    TickerFeed(DoubleSupplier noise, List<String> symbols) {
        this.noise = noise;
        this.symbols = List.copyOf(symbols);
        this.prices = new java.util.HashMap<>();
        for (String symbol : this.symbols) {
            prices.put(symbol, 100.0);
        }
    }

    /**
     * Registers a listener; the returned runnable unsubscribes (the emitter's
     * terminal callbacks call it — completion, timeout, and error all funnel
     * here, so a finished stream never leaves a ghost listener behind).
     */
    public Runnable subscribe(Consumer<Tick> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Live subscriber count — the state endpoint's "is the feed paused" answer. */
    public int listenerCount() {
        return listeners.size();
    }

    /** Total ticks generated. Only generation with listeners counts — the counter IS the pause proof. */
    public synchronized long ticksGenerated() {
        return ticksGenerated;
    }

    /**
     * One pace step. THE PAUSE: with no listeners this returns before doing
     * anything — no price walk, no ring growth, no sends. The pump may keep
     * beating; the feed decides what a beat means.
     */
    public synchronized void tick(Instant now) {
        if (listeners.isEmpty()) {
            return;
        }
        String symbol = symbols.get(cursor % symbols.size());
        cursor++;
        double previous = prices.get(symbol);
        double drift = (noise.getAsDouble() - 0.5) * previous * MAX_DRIFT_FRACTION;
        double price = Math.round((previous + drift) * 100.0) / 100.0;
        prices.put(symbol, price);
        Tick tick = new Tick(++seq, symbol, price, Math.round(drift * 100.0) / 100.0, now);
        ring.addLast(tick);
        while (ring.size() > RING_CAPACITY) {
            ring.removeFirst();
        }
        ticksGenerated++;
        deliver(tick);
    }

    /**
     * The Last-Event-ID resume window: every tick after {@code lastSeq} that
     * the ring still holds, oldest first. Bounded by construction — a resume
     * older than the window gets whatever survived (the honest at-most-once
     * boundary this topic teaches; the alternative, unbounded server memory
     * per client, is the non-answer).
     */
    public synchronized List<Tick> replayAfter(long lastSeq) {
        if (ring.isEmpty() || ring.peekLast().seq() <= lastSeq) {
            return List.of();
        }
        return ring.stream().filter(tick -> tick.seq() > lastSeq).toList();
    }

    /** The newest sequence issued — the event id a reconnecting client will present back. */
    public synchronized long latestSeq() {
        return seq;
    }

    /**
     * Delivery with the slow-consumer policy: a listener that THROWS is
     * dropped, not retried — in the real wiring the throw is a dead
     * emitter's IOException, and the drop is the whole backpressure story.
     */
    private void deliver(Tick tick) {
        for (Consumer<Tick> listener : listeners) {
            try {
                listener.accept(tick);
            } catch (RuntimeException dead) {
                listeners.remove(listener);
            }
        }
    }
}
