package guide.data.reliability;

import guide.domain.reliability.BreakerOpenException;
import guide.domain.reliability.BreakerPort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The reliability demo's breaker, ADAPTED: the only class in the topic that
 * knows Resilience4j exists. It owns the {@link CircuitBreaker} instance
 * and the five knobs it was built with, implements the domain's
 * {@link BreakerPort}, and translates the one library exception that
 * matters into the domain's {@link BreakerOpenException} — the dependency
 * rule from topic 32, applied to a library instead of a layer: the domain
 * defines the port, data adapts the machinery, config wires them.
 *
 * The instance is built with {@link CircuitBreaker#of} instead of the
 * starter's autoconfigured Registry on purpose: the demo's wiring must be
 * IDENTICAL under the full app and under a @WebMvcTest slice (slices skip
 * the r4j autoconfiguration), and an explicit object has no context-shaped
 * failure modes.
 */
public final class Resilience4jBreakerAdapter implements BreakerPort {

    static final String BREAKER_NAME = "reliability-demo";

    /**
     * The OPEN-state wait, owned HERE and served through the snapshot: r4j
     * wraps it into an interval function inside CircuitBreakerConfig (no
     * getter hands it back), so the adapter keeps the knob as one constant
     * the builder and the report both read — the number on screen cannot
     * drift from the number the breaker runs with.
     */
    static final Duration WAIT_IN_OPEN = Duration.ofSeconds(10);

    private final CircuitBreaker breaker;

    public Resilience4jBreakerAdapter() {
        this.breaker = CircuitBreaker.of(BREAKER_NAME, CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(WAIT_IN_OPEN)
                .permittedNumberOfCallsInHalfOpenState(1)
                .slidingWindowSize(10)
                .build());
    }

    @Override
    public <T> T execute(Supplier<T> call) {
        try {
            return breaker.decorateSupplier(call).get();
        } catch (CallNotPermittedException rejected) {
            throw new BreakerOpenException(rejected);
        }
    }

    @Override
    public String state() {
        return breaker.getState().name();
    }

    @Override
    public Snapshot snapshot() {
        CircuitBreakerConfig config = breaker.getCircuitBreakerConfig();
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        return new Snapshot(breaker.getState().name(),
                config.getFailureRateThreshold(), config.getMinimumNumberOfCalls(),
                WAIT_IN_OPEN.toMillis(), config.getSlidingWindowSize(),
                config.getPermittedNumberOfCallsInHalfOpenState(),
                metrics.getNumberOfSuccessfulCalls(), metrics.getNumberOfFailedCalls(),
                metrics.getNumberOfNotPermittedCalls(), metrics.getFailureRate());
    }

    @Override
    public void forceHalfOpen() {
        breaker.transitionToHalfOpenState();
    }

    @Override
    public void reset() {
        breaker.reset();
    }
}
