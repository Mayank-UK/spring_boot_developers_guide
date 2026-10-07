package guide.domain.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Use case: the console's consumer door — XREADGROUP, run every delivery
 * through the idempotent consumer, and (unless the operator is staging a
 * crash) XACK. The {@code replayPending} flag is the crash-recovery read:
 * same consumer, offset "0" — "give me back what I was holding when I
 * died", which is exactly what a worker does on restart, and exactly how
 * the demo stages redeliveries for the idempotency proof.
 */
public class ConsumeFromStream {

    private final EventStream stream;
    private final IdempotentConsumer consumer;

    public ConsumeFromStream(EventStream stream, IdempotentConsumer consumer) {
        this.stream = stream;
        this.consumer = consumer;
    }

    /** One consume call's answer: what was delivered, what it did, what acked. */
    public record ConsumeView(List<IdempotentConsumer.Outcome> deliveries, long acked,
            boolean ackedRequested, Map<String, Long> counters, long duplicatesSeen) {
    }

    public ConsumeView execute(int max, boolean ack, boolean replayPending) {
        int count = Math.clamp(max, 1, 100);
        List<EventStream.Delivery> delivered =
                stream.readGroup(EventStream.CONSUMER, count, replayPending);
        List<IdempotentConsumer.Outcome> outcomes = new ArrayList<>(delivered.size());
        List<String> ids = new ArrayList<>(delivered.size());
        for (EventStream.Delivery delivery : delivered) {
            outcomes.add(consumer.process(delivery));
            ids.add(delivery.id());
        }
        long acked = ack && !ids.isEmpty() ? stream.ack(ids) : 0L;
        return new ConsumeView(outcomes, acked, ack,
                consumer.counters().snapshot(), consumer.counters().duplicates());
    }
}
