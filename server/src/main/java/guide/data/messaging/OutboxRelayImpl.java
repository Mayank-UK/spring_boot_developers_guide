package guide.data.messaging;

import guide.domain.messaging.OutboxRelay;
import guide.domain.messaging.OutboxStore;
import guide.domain.messaging.OutboxStore.OutboxEntry;
import guide.domain.messaging.EventStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The relay ADAPTER: drain the outbox to the stream, one bounded batch per
 * call. Three load-bearing decisions:
 *
 * SINGLE-FLIGHT: an AtomicBoolean CAS opens each batch; a concurrent caller
 * loses the CAS and gets {@code skippedInFlight} back without touching the
 * store. SKIP LOCKED already makes double DELIVERY impossible — two relays
 * can never claim the same row — so this guard is about duplicate WORK and
 * predictability (one ticker, one manual /relay click racing it, one winner
 * per instant). The unit test pins the guard with a latch: a second relay
 * call while a batch is open MUST take the skip path.
 *
 * MARK AFTER PUBLISH: a row is marked processed only AFTER the broker
 * accepted the XADD — never before. Crash between claim and mark leaves the
 * row claimable again (attempts already bumped): redelivered, recognized by
 * any idempotent consumer, harmless. That ordering is the at-least-once
 * spine of the whole pattern.
 *
 * DEAD-LETTER AFTER N: a row whose attempts have passed
 * {@link OutboxRelay#MAX_ATTEMPTS} without completing is poison — the
 * classic case is an event no consumer will ever accept, and the honest
 * answer is to stop cycling it: ship the event to the dead-letter stream
 * for human eyes, mark the row processed (its attempts say WHY), move on.
 * The threshold is a claim count here, which is the teaching simplification
 * — a production relay dead-letters on NON-RETRYABLE failures (unserializable
 * event, oversized payload) and retries transient ones forever; conflating
 * the two would let a broker outage poison the entire queue, a caveat the
 * topic states out loud.
 */
public final class OutboxRelayImpl implements OutboxRelay {

    private final OutboxStore store;
    private final EventStream stream;
    private final BooleanSupplier pollerEnabled;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public OutboxRelayImpl(OutboxStore store, EventStream stream,
            BooleanSupplier pollerEnabled) {
        this.store = Objects.requireNonNull(store, "store");
        this.stream = Objects.requireNonNull(stream, "stream");
        this.pollerEnabled = Objects.requireNonNull(pollerEnabled, "pollerEnabled");
    }

    @Override
    public RelayReport relayNextBatch(Instant now, int limit) {
        // The single-flight gate: one batch at a time, per process.
        if (!inFlight.compareAndSet(false, true)) {
            return new RelayReport(0, 0, 0, true, List.of(), now);
        }
        try {
            List<OutboxEntry> batch = store.claimPending(limit);
            int published = 0;
            int deadLettered = 0;
            List<String> streamIds = new ArrayList<>(batch.size());
            List<Long> processedIds = new ArrayList<>(batch.size());
            for (OutboxEntry entry : batch) {
                if (entry.attempts() > MAX_ATTEMPTS) {
                    // Poison: claimed past the threshold without completing.
                    // Park it in the DLQ for human eyes, mark it done, move on.
                    stream.appendDead(entry.event(), "claimed " + entry.attempts()
                            + " times without completing — dead-lettered by the relay");
                    deadLettered++;
                } else {
                    // The delivery. Only after the broker accepted it does
                    // the row leave the queue — crash before that and the
                    // row is simply claimed again.
                    streamIds.add(stream.append(entry.event()).streamId());
                    published++;
                }
                processedIds.add(entry.id());
            }
            if (!processedIds.isEmpty()) {
                store.markProcessed(processedIds);
            }
            return new RelayReport(batch.size(), published, deadLettered, false, streamIds, now);
        } finally {
            inFlight.set(false);
        }
    }

    @Override
    public boolean canRun() {
        return pollerEnabled.getAsBoolean();
    }
}
