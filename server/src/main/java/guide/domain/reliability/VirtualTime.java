package guide.domain.reliability;

import java.time.Duration;
import java.time.Instant;

/**
 * The demo's clock. Real latency is the enemy of a deterministic teaching
 * demo: a slow-mode call that genuinely slept 1.5 seconds would make the
 * tour sluggish and every test wall-clock-bound. So time here is a SETTABLE
 * INSTANT the caller advances by booking virtual durations — the same
 * "time is a parameter" seam the webhooks engine uses with its injected
 * Clock, one step further: the whole timeline is virtual.
 *
 * One honest consequence, taught beside the demo: Resilience4j's own
 * OPEN→HALF_OPEN timer runs on the REAL clock inside the library, so
 * advancing this instant does not age the breaker's wait window — the
 * deterministic door to HALF_OPEN is the manual transition rail
 * (transitionToHalfOpenState), which is exactly what an operator endpoint
 * calls in production.
 */
public final class VirtualTime {

    private final Instant anchor;
    private volatile Instant now;

    public VirtualTime(Instant anchor) {
        this.anchor = java.util.Objects.requireNonNull(anchor, "anchor");
        this.now = anchor;
    }

    /** The current virtual instant. */
    public Instant now() {
        return now;
    }

    /** Milliseconds since the anchor — the demo timeline's coordinate system. */
    public long elapsedMs() {
        return Duration.between(anchor, now).toMillis();
    }

    /** Where one past instant sits on the timeline (ms since the anchor). */
    public long elapsedMsOf(Instant at) {
        return Duration.between(anchor, at).toMillis();
    }

    /** Book a span of simulated time; returns the instant the span ends at. */
    public synchronized Instant advance(Duration span) {
        now = now.plus(span);
        return now;
    }

    /** Back to the anchor — part of the demo's reset. */
    public void reset() {
        now = anchor;
    }
}
