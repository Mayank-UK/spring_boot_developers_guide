package guide.domain.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Use case: the visibility-timeout takeover — claim entries that have sat
 * un-acked past an idle threshold, then decide each one's fate:
 *
 * - POISON (this claim pushes deliveries past {@link #MAX_DELIVERIES}): the
 *   entry has bounced through workers long enough. Dead-letter it — XADD to
 *   the DLQ stream with a reason, XACK it out of the group — so it stops
 *   cycling and a human can look at it. This is the SQS-lineage
 *   maxReceiveCount / RabbitMQ-lineage dead-letter-exchange behavior,
 *   rebuilt on stream primitives.
 * - REPROCESSABLE: run it through the idempotent consumer (a claim IS a
 *   redelivery — the dedupe set is what keeps the projection honest) and,
 *   unless the operator is staging another crash, ack it.
 *
 * The poison threshold counts DELIVERIES (the group's own counter, bumped
 * by every claim), not attempts-in-a-database — same arithmetic, different
 * substrate than the outbox's dead-letter rule; the symmetry is deliberate
 * and the topic points at it.
 */
public class ClaimIdleEntries {

    /** Deliveries tolerated before an entry is poison (read + 2 claims). */
    public static final int MAX_DELIVERIES = 3;

    private final EventStream stream;
    private final IdempotentConsumer consumer;

    public ClaimIdleEntries(EventStream stream, IdempotentConsumer consumer) {
        this.stream = stream;
        this.consumer = consumer;
    }

    /** One takeover's ledger: reprocessed, dead-lettered, counters. */
    public record ClaimView(String claimedBy, long minIdleMs,
            List<IdempotentConsumer.Outcome> reprocessed,
            List<EventStream.DeadLetter> deadLettered,
            long acked, Map<String, Long> counters) {
    }

    public ClaimView execute(long minIdleMs, boolean ack) {
        EventStream.ClaimedEntries claimed =
                stream.claimIdle(EventStream.CLAIM_CONSUMER, Duration.ofMillis(Math.max(0, minIdleMs)));
        List<IdempotentConsumer.Outcome> reprocessed = new ArrayList<>();
        List<EventStream.DeadLetter> deadLettered = new ArrayList<>();
        List<String> toAck = new ArrayList<>();
        for (EventStream.Delivery delivery : claimed.reprocessable()) {
            reprocessed.add(consumer.process(delivery));
            if (ack) {
                toAck.add(delivery.id());
            }
        }
        for (EventStream.Delivery poison : claimed.poison()) {
            deadLettered.add(new EventStream.DeadLetter(poison.id(),
                    poison.event().eventId(), poison.event().eventType(),
                    "delivered " + poison.deliveryCount() + " times without an ack — poison",
                    null, poison.event()));
            stream.appendDead(poison.event(),
                    "delivered " + poison.deliveryCount() + " times without an ack");
            toAck.add(poison.id());
        }
        long acked = toAck.isEmpty() ? 0L : stream.ack(toAck);
        return new ClaimView(claimed.claimedBy(), claimed.minIdleMs(),
                reprocessed, deadLettered, acked, consumer.counters().snapshot());
    }
}
