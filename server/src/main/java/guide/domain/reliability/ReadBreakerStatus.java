package guide.domain.reliability;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the breaker's observable surface: state, the config knobs that
 * govern it, the live window metrics, the recent call reports, and a
 * reading that narrates WHAT THE NUMBERS MEAN — generated from the
 * breaker's own {@link BreakerPort#snapshot()} so the narration cannot
 * drift from the machinery (the same rule the perf topic applies to its
 * compression posture). The use case knows the breaker only as domain
 * data; which library produced the numbers is the adapter's business.
 */
public final class ReadBreakerStatus {

    /** The knobs the demo runs the breaker with, exactly as configured. */
    public record ConfigView(float failureRateThreshold, int minimumNumberOfCalls,
            long waitDurationMs, int slidingWindowSize, int permittedInHalfOpen) {
    }

    /** The breaker's live window: what it has judged so far. */
    public record MetricsView(long successes, long failures, long notPermitted,
            Double failureRate) {
    }

    /** Everything the demo's status panel renders. */
    public record BreakerStatus(String state, String downstreamMode, boolean injectFailureRail,
            long virtualNowMs, ConfigView config, MetricsView metrics, Long openedAtMs,
            Long probeEligibleAtMs, List<ResilientCaller.CallReport> recentCalls,
            List<String> reading) {
    }

    private final ResilientCaller caller;
    private final DownstreamTheater theater;
    private final VirtualTime time;
    private final BreakerPort breaker;

    public ReadBreakerStatus(ResilientCaller caller, DownstreamTheater theater, VirtualTime time,
            BreakerPort breaker) {
        this.caller = caller;
        this.theater = theater;
        this.time = time;
        this.breaker = breaker;
    }

    public BreakerStatus execute() {
        BreakerPort.Snapshot snapshot = breaker.snapshot();

        long successes = snapshot.successes();
        long failures = snapshot.failures();
        long judged = successes + failures;
        Double failureRate = judged >= snapshot.minimumNumberOfCalls()
                ? (double) snapshot.failureRate()
                : null;

        String state = snapshot.state();
        Optional<Long> openedAtMs = state.equals("OPEN")
                ? caller.openedAt().map(at -> time.elapsedMsOf(at))
                : Optional.empty();
        Long probeEligibleAtMs = openedAtMs.map(opened -> opened + snapshot.waitDurationMs())
                .orElse(null);

        List<String> reading = new ArrayList<>();
        switch (state) {
            case "OPEN" -> reading.add("OPEN since t=" + openedAtMs.orElse(0L) + "ms (virtual) — calls are rejected at the door; the wait window ends at t="
                    + probeEligibleAtMs + "ms, or force the half-open probe now.");
            case "HALF_OPEN" -> reading.add("HALF_OPEN — the next " + snapshot.permittedInHalfOpen()
                    + " probe call(s) decide: success closes the breaker, failure re-opens it.");
            default -> reading.add("CLOSED — calls pass through; the window holds " + successes
                    + " success(es) and " + failures + " failure(s) and starts judging at "
                    + snapshot.minimumNumberOfCalls() + " recorded calls.");
        }
        reading.add("trip condition: failure rate >= " + snapshot.failureRateThreshold()
                + "% over the last " + snapshot.slidingWindowSize() + " calls"
                + (failureRate == null ? " (not enough calls judged yet)."
                        : " — currently " + String.format("%.1f", failureRate) + "%."));
        if (theater.globalRailOn()) {
            reading.add("the app-wide injectFailure rail is ON — every attempt fails regardless of the dial.");
        }

        return new BreakerStatus(state, theater.mode().name().toLowerCase(), theater.globalRailOn(),
                time.elapsedMs(),
                new ConfigView(snapshot.failureRateThreshold(), snapshot.minimumNumberOfCalls(),
                        snapshot.waitDurationMs(), snapshot.slidingWindowSize(),
                        snapshot.permittedInHalfOpen()),
                new MetricsView(successes, failures, snapshot.notPermitted(), failureRate),
                openedAtMs.orElse(null), probeEligibleAtMs, caller.recent(), List.copyOf(reading));
    }
}
