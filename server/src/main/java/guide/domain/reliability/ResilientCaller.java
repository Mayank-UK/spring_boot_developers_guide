package guide.domain.reliability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Topic 39's engine: ONE downstream call, executed through the whole
 * resilience stack in the order the topic teaches it — the CIRCUIT BREAKER
 * outside, the RETRY loop (with its per-attempt timeout and backoff)
 * inside. The breaker judges call outcomes; retries are an implementation
 * detail of a single call, which is why they live inside it.
 *
 * The breaker itself is the DOMAIN-OWNED {@link BreakerPort} — whether it
 * is Resilience4j or anything else is the adapter's secret (topic 32's
 * dependency rule); a refused call arrives as {@link BreakerOpenException},
 * the domain's own type.
 *
 * Every temporal claim is booked against the {@link VirtualTime}: an
 * attempt's latency, the backoff between attempts — no thread ever sleeps,
 * so the timeline is byte-for-byte reproducible in the demo AND in tests.
 *
 * The caller also keeps the theater's observable memory: the last few call
 * reports (the "recent outcomes" the status endpoint serves) and the
 * instant it first observed the breaker OPEN (virtual time), so the report
 * can name the moment the wait window ends.
 *
 * Single-flight on purpose: the call path is synchronized — one call at a
 * time is the simplest possible bulkhead (concurrency 1), and it keeps the
 * theater's counters exact.
 */
public final class ResilientCaller {

    public static final int RECENT_LIMIT = 8;

    /** One downstream attempt, placed on the virtual timeline. */
    public record AttemptReport(int attempt, String outcome, long startedAtMs, long endedAtMs,
            String detail) {
    }

    /** One full call: what the caller asked, what the stack did, what it cost. */
    public record CallReport(String outcome, String breakerStateBefore, String breakerStateAfter,
            List<AttemptReport> attempts, long totalElapsedMs, int downstreamAttempts) {
    }

    /** What the retry loop produced, before the caller frames it with breaker state. */
    private record AttemptsRun(String outcome, List<AttemptReport> attempts, long totalElapsedMs) {
    }

    /** Carries the run out of the supplier so the breaker can record the failure. */
    private static final class CallExhaustedException extends RuntimeException {
        final AttemptsRun run;

        CallExhaustedException(AttemptsRun run) {
            super("the call exhausted its attempts");
            this.run = run;
        }
    }

    private final DownstreamTheater theater;
    private final BreakerPort breaker;
    private final RetryPolicy policy;
    private final VirtualTime time;
    private final List<CallReport> recent = new ArrayList<>();
    private Instant openedAt;

    public ResilientCaller(DownstreamTheater theater, BreakerPort breaker, RetryPolicy policy,
            VirtualTime time) {
        this.theater = Objects.requireNonNull(theater, "theater");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.time = Objects.requireNonNull(time, "time");
    }

    /**
     * One resilient call. REJECTED_BY_BREAKER is not an error — it is the
     * fast, cheap answer an OPEN breaker exists to give (zero downstream
     * attempts, zero virtual time spent).
     */
    public synchronized CallReport call() {
        String before = breaker.state();
        try {
            AttemptsRun run = breaker.execute(this::runAttempts);
            return remember(frame(run, before), before);
        } catch (BreakerOpenException rejected) {
            CallReport report = new CallReport("REJECTED_BY_BREAKER", before,
                    breaker.state(), List.of(), 0L, 0);
            return remember(report, before);
        } catch (CallExhaustedException exhausted) {
            return remember(frame(exhausted.run, before), before);
        }
    }

    /** The retry loop the breaker wraps: attempts, timeouts, backoffs — all virtual. */
    private AttemptsRun runAttempts() {
        List<AttemptReport> attempts = new ArrayList<>();
        long callStartedAt = time.elapsedMs();
        for (int attempt = 1; attempt <= RetryPolicy.MAX_ATTEMPTS; attempt++) {
            long startedAt = time.elapsedMs();
            DownstreamTheater.AttemptOutcome outcome = theater.attempt();
            boolean timedOut = outcome.wouldTake().compareTo(RetryPolicy.ATTEMPT_TIMEOUT) > 0;
            time.advance(policy.billed(outcome.wouldTake()));
            attempts.add(new AttemptReport(attempt,
                    timedOut ? "timed_out" : outcome.success() ? "success" : "failed",
                    startedAt, time.elapsedMs(), outcome.detail()));
            if (!timedOut && outcome.success()) {
                return new AttemptsRun("SUCCESS", List.copyOf(attempts),
                        time.elapsedMs() - callStartedAt);
            }
            if (policy.canRetry(attempt)) {
                time.advance(RetryPolicy.backoffAfter(attempt));
            }
        }
        String outcome = attempts.getLast().outcome().equals("timed_out") ? "TIMED_OUT" : "FAILED";
        // the breaker records what its supplier THROWS — failure must not
        // return politely
        throw new CallExhaustedException(new AttemptsRun(outcome, List.copyOf(attempts),
                time.elapsedMs() - callStartedAt));
    }

    private CallReport frame(AttemptsRun run, String stateBefore) {
        return new CallReport(run.outcome(), stateBefore, breaker.state(),
                run.attempts(), run.totalElapsedMs(), run.attempts().size());
    }

    private CallReport remember(CallReport report, String stateBefore) {
        if ("OPEN".equals(breaker.state())) {
            // a NEW open period (not OPEN before this call) stamps a NEW wait
            // window — a failed half-open probe restarting the clock is the
            // case that matters
            if (openedAt == null || !"OPEN".equals(stateBefore)) {
                openedAt = time.now();
            }
        } else {
            openedAt = null;
        }
        recent.add(report);
        while (recent.size() > RECENT_LIMIT) {
            recent.removeFirst();
        }
        return report;
    }

    /** The instant the caller first observed OPEN (virtual), empty while not OPEN. */
    public Optional<Instant> openedAt() {
        return Optional.ofNullable(openedAt);
    }

    /** Newest-first copy of the recent call reports. */
    public List<CallReport> recent() {
        List<CallReport> copy = new ArrayList<>(recent);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    /** Breaker state + metrics reset; the recent list and the OPEN memory go too. */
    public synchronized void reset() {
        breaker.reset();
        openedAt = null;
        recent.clear();
    }
}
