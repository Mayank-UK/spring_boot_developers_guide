package guide.domain.reliability;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Topic 39's retry budget: one place that owns HOW a failing call is
 * re-attempted — how many times, with what per-attempt timeout, and how the
 * pause between attempts grows. The caller ({@link ResilientCaller}) owns
 * WHETHER a call is attempted at all (the circuit breaker's job); this class
 * owns the shape of the attempts once one is permitted.
 *
 * The backoff curve is the same one the webhooks delivery engine runs
 * (guide.domain.webhooks.DeliveryEngine): exponential from a base, doubled
 * per failure, capped — exponential growth shows respect for a struggling
 * downstream, the cap shows respect for your own freshness budget. Each
 * topic carries its own copy so the two engines can tune independently;
 * the CURVE is the shared decision, not the constant.
 *
 * Pure values throughout — everything here is arithmetic against instants
 * the caller passes in, so tests never sleep.
 */
public final class RetryPolicy {

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration ATTEMPT_TIMEOUT = Duration.ofMillis(500);

    static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    /** True when one more attempt is allowed (attemptNumber is 1-based). */
    public boolean canRetry(int attemptNumber) {
        return attemptNumber < MAX_ATTEMPTS;
    }

    /**
     * The pause after the Nth failed attempt: 1s, 2s, 4s, then flat —
     * a pure function of the failure count, mirroring the webhook engine's
     * curve so the two topics teach one shape.
     */
    public static Duration backoffAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("backoff starts after the first failure");
        }
        long exponent = Math.min(failedAttempts - 1, 3);
        Duration delay = BASE_BACKOFF.multipliedBy(1L << exponent);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /**
     * What the timeout actually decides per attempt: a downstream that
     * would take longer than the budget is abandoned AT the budget — the
     * caller books {@link #ATTEMPT_TIMEOUT}, not the full latency, exactly
     * like a real client that gives up on the socket and moves on.
     */
    public Duration billed(Duration wouldTake) {
        return wouldTake.compareTo(ATTEMPT_TIMEOUT) > 0 ? ATTEMPT_TIMEOUT : wouldTake;
    }

    /** Modes the demo's downstream dial offers, with their canned behaviors. */
    public enum DownstreamMode {
        HEALTHY, FLAKY, DOWN, SLOW;

        /** Case-insensitive parse for the demo's dial endpoint; empty = show the caller how. */
        public static Optional<DownstreamMode> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            for (DownstreamMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }

        /** Every accepted dial spelling, for error messages and the client's buttons. */
        public static List<String> names() {
            return List.of("healthy", "flaky", "down", "slow");
        }
    }
}
