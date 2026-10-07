package guide.domain.reliability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The simulated downstream the whole topic beats against. A real service
 * calls a database, a cache, another HTTP hop; this theater MODELS one with
 * four personalities the demo can dial — and the latency is VIRTUAL: an
 * attempt reports how long it would take, and the caller books that span
 * against the shared {@link VirtualTime}. Nothing ever sleeps, which is
 * what makes every timeline in the demo (and every assertion in its tests)
 * deterministic.
 *
 * The fifth behavior is not a mode but a RAIL: the app-wide failure
 * injection switch (ScenarioStore's injectFailure, PATCH /api/demo/scenario)
 * arrives here as a plain BooleanSupplier. When the global rail is on, every
 * attempt fails no matter the dial — the chaos section's point: the same
 * door the scenario API opens is the door a chaos tool pulls.
 *
 * FLAKY alternates per ATTEMPT on purpose: with the retry policy's three
 * attempts, a flaky downstream fails attempt one and passes attempt two —
 * the call SUCCEEDS and the breaker never learns anything happened. That is
 * the retry/breaker division of labor, demonstrated by arithmetic.
 */
public final class DownstreamTheater {

    /** One attempt's simulated result: what happened and how long it would have taken. */
    public record AttemptOutcome(boolean success, Duration wouldTake, String detail) {
    }

    private final BooleanSupplier globalFailureRail;
    private final AtomicInteger attemptCounter = new AtomicInteger();
    private volatile RetryPolicy.DownstreamMode mode = RetryPolicy.DownstreamMode.HEALTHY;

    /** @param globalFailureRail the ScenarioStore injectFailure switch, as a pure seam. */
    public DownstreamTheater(BooleanSupplier globalFailureRail) {
        this.globalFailureRail = globalFailureRail;
    }

    /** The dial's current position — the status report reads it back. */
    public RetryPolicy.DownstreamMode mode() {
        return mode;
    }

    /** True when the app-wide failure injection rail is ON (the chaos section's door). */
    public boolean globalRailOn() {
        return globalFailureRail.getAsBoolean();
    }

    /** Turn the dial; the use case validates the input before it reaches here. */
    public void setMode(RetryPolicy.DownstreamMode next) {
        mode = java.util.Objects.requireNonNull(next, "next");
    }

    /**
     * One simulated downstream attempt. Deterministic by construction:
     * FLAKY alternates on a monotonic counter, the other modes are constant.
     */
    public AttemptOutcome attempt() {
        int attemptNumber = attemptCounter.incrementAndGet();
        if (globalFailureRail.getAsBoolean()) {
            return new AttemptOutcome(false, Duration.ofMillis(40),
                    "injected by the global failure rail (injectFailure=true)");
        }
        return switch (mode) {
            case HEALTHY -> new AttemptOutcome(true, Duration.ofMillis(150),
                    "200 OK in " + 150 + "ms");
            case FLAKY -> attemptNumber % 2 == 1
                    ? new AttemptOutcome(false, Duration.ofMillis(200), "503 from upstream (transient)")
                    : new AttemptOutcome(true, Duration.ofMillis(120), "200 OK — the flake passed");
            case DOWN -> new AttemptOutcome(false, Duration.ofMillis(30),
                    "connect refused — the downstream is gone");
            case SLOW -> new AttemptOutcome(true, Duration.ofMillis(1500),
                    "would answer, but 1500ms exceeds the 500ms budget");
        };
    }

    /** Full reset (dial, flake counter) — the demo's reset endpoint calls this. */
    public void reset() {
        mode = RetryPolicy.DownstreamMode.HEALTHY;
        attemptCounter.set(0);
    }

    /** Kept as a named type so the config reads as prose: "the scenario rail". */
    @FunctionalInterface
    public interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
