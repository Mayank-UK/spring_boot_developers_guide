package guide.domain.reliability;

import java.util.function.Supplier;

/**
 * The breaker as the domain sees it — TOPIC 32's dependency rule applied to
 * a library, not a layer: the use cases speak in domain vocabulary (state
 * names, counters, a typed rejection), and the Resilience4j machinery
 * lives behind this port in an adapter under data/. The domain never
 * learns whether the breaker is Resilience4j, a hand-rolled state machine,
 * or a stub in a test — which is exactly what makes each of those a
 * one-line wiring change at the composition root.
 */
public interface BreakerPort {

    /**
     * Runs one call through the breaker. A refused call (breaker OPEN)
     * surfaces as {@link BreakerOpenException} — the DOMAIN's own type, so
     * no library exception ever crosses this boundary. Any exception the
     * call itself throws propagates unchanged (the breaker has recorded
     * it).
     */
    <T> T execute(Supplier<T> call);

    /** CLOSED, OPEN, or HALF_OPEN — the state machine's current verdict. */
    String state();

    /** Everything the status report renders, as plain data. */
    Snapshot snapshot();

    /** Forces OPEN → HALF_OPEN — the ops rail (the use case guards the call). */
    void forceHalfOpen();

    /** Back to CLOSED with an empty window — the demo's reset path. */
    void reset();

    /**
     * The breaker as data: the knobs it was built with plus the live
     * window. {@code failureRate} is the RAW rate; judging whether enough
     * calls exist to trust it ({@code judged >= minimumNumberOfCalls}) is
     * the reader's call.
     */
    record Snapshot(String state, float failureRateThreshold, int minimumNumberOfCalls,
            long waitDurationMs, int slidingWindowSize, int permittedInHalfOpen,
            long successes, long failures, long notPermitted, float failureRate) {
    }
}
