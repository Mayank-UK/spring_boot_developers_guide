package guide.domain.caching;

import java.time.Duration;

/**
 * The ARTIFICIAL EXPENSE SEAM — an injectable "this read is slow" pause.
 *
 * Real caches exist to skip expensive work: a gnarly aggregate, a fan-out of
 * queries, a call to a slow neighbor. Teaching caches honestly means owning
 * an expense you can SEE — so the expensive read this topic caches carries a
 * deliberate {@link #pause()} at its heart (150 ms in the wiring: visible in
 * a demo, tolerable in a test). The seam exists so tests can inject
 * {@link #none()} and prove caching with call COUNTS and TTL arithmetic
 * instead of wall-clock races — a cached read is proven by the compute NOT
 * happening, never by asserting "the second call felt faster".
 */
@FunctionalInterface
public interface Delayer {

    /** Blocks for the configured expense; interrupts restore the flag and return. */
    void pause();

    /** A fixed pause — the default wiring (topic 21 demos use 150 ms). */
    static Delayer fixed(Duration delay) {
        long millis = delay.toMillis();
        return () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** The test shape: no pause at all — caching proofs run at full speed. */
    static Delayer none() {
        return () -> {
        };
    }
}
