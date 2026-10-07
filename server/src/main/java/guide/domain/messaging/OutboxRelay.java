package guide.domain.messaging;

import java.time.Instant;
import java.util.List;

/**
 * The relay PORT: drain the outbox to the stream, one bounded batch at a
 * time. The implementation (guide.data.messaging.OutboxRelayImpl) owns two
 * guarantees the demo proves live:
 *
 * - SINGLE-FLIGHT: at most one relay batch runs at a time per process. A
 *   CAS guard rejects concurrent callers with {@code skippedInFlight} —
 *   duplicate WORK is wasteful even when SKIP LOCKED already guarantees
 *   duplicate DELIVERY cannot happen.
 * - DEAD-LETTER AFTER N: a row claimed more than {@link #MAX_ATTEMPTS}
 *   times without completing is poison (the classic case: an event nothing
 *   will ever accept). The relay parks it — processed, attempts at the
 *   threshold — and ships the event to the dead-letter stream for human
 *   eyes instead of blocking the queue behind it forever.
 *
 * relayNextBatch takes {@code now} as a PARAMETER rather than reading the
 * clock: the same method serves the @Scheduled tick (real clock) and tests
 * (constructed instants) — the fake-clock seam the webhook engine taught.
 */
public interface OutboxRelay {

    /**
     * The dead-letter threshold: a row surviving this many claims without
     * completion is poison. Three is the teaching value; production relays
     * pair this with backoff (a claim every second × 3 is a very fast
     * verdict) and with NON-RETRYABLE error classification — a broker
     * outage must not dead-letter the whole queue, only a genuinely bad
     * event should.
     */
    int MAX_ATTEMPTS = 3;

    /** One batch's ledger — the shape POST /relay answers with. */
    record RelayReport(int claimed, int published, int deadLettered, boolean skippedInFlight,
            List<String> streamIds, Instant at) {
    }

    /** Publish pending rows to the stream, then mark them processed. */
    RelayReport relayNextBatch(Instant now, int limit);

    /** The tick's gate: false until live activation succeeded (and the msg
     * area is in live mode) — the poller does nothing before that. */
    boolean canRun();
}
