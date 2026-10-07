package guide.domain.jobs;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * The retry cadence as pure math — the same shape the webhook engine
 * taught, tuned for jobs: after failure n, wait 1s · 2^(n−1) capped at 4s,
 * so the curve this queue walks is 1s, 2s, 4s, 4s… Growth respects the
 * struggling dependency (backing off is the point); the cap respects YOUR
 * freshness promises (an unbounded curve leaves work "in flight" for days).
 * With the demo's default budget of three attempts the whole episode spans
 * 1 + 2 = 3 seconds of waiting — long enough to watch, short enough to
 * demo.
 *
 * Jitter rides on top as an injected fraction of the delay (0–100%), so
 * competing workers that failed together do not retry in lockstep — the
 * thundering-herd decorrelation the messaging and webhooks topics measured.
 * Tests inject {@code () -> 0.0} for exact schedules; production wiring
 * injects {@code new Random()::nextDouble}.
 */
public final class JobBackoff {

    public static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(4);

    private JobBackoff() {
    }

    /**
     * The curve: 1s, 2s, 4s, then flat. Called with the number of attempts
     * that have already failed (≥ 1 — backoff starts after the first
     * failure; an immediate retry teaches the dependency nothing).
     */
    public static Duration delayAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("backoff starts after the first failure");
        }
        long exponent = Math.min(failedAttempts - 1, 2);
        Duration delay = BASE_BACKOFF.multipliedBy(1L << exponent);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /**
     * Jitter as a pure function of the delay and a uniform [0,1) fraction:
     * the share of the delay added on top. Deterministic when the fraction
     * is — that seam is the whole reason jitter is a parameter.
     */
    public static Duration withJitter(Duration delay, DoubleSupplier fraction) {
        long extra = (long) (delay.toMillis() * fraction.getAsDouble());
        return delay.plusMillis(extra);
    }
}
