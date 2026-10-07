package guide.data.messaging;

import guide.domain.messaging.DomainEvent;
import guide.domain.messaging.OutboxStore;
import guide.domain.messaging.TeachingEventPublisher;
import java.util.Objects;

/**
 * THE OUTBOX-BACKED PUBLISHER — a drop-in replacement for the direct one
 * that changes the durability story completely: publish() INSERTs a row
 * into the outbox table via JdbcTemplate instead of touching the broker.
 *
 * The entire mechanism is one property of JdbcTemplate: it borrows the
 * CURRENT transaction's thread-bound connection. Inside a caller's
 * @Transactional method, that makes this INSERT part of the caller's
 * commit — the event becomes exactly as durable as the business mutation
 * beside it, which is the whole guarantee. Outside a transaction the INSERT
 * auto-commits (a durable enqueue, atomic with nothing) — the pattern's
 * guarantee is a property of the CALLER's bracket, not of this class, and
 * the demo's transactional adapter is what provides the bracket.
 *
 * The returned PublishedEvent deliberately carries a null streamId and the
 * stream name "outbox": nothing has been delivered yet — the relay will
 * assign the real stream id later, and callers that need it must ask the
 * outbox, not the publisher.
 */
public final class OutboxEventPublisher implements TeachingEventPublisher {

    /** The honest stream name for an event that has not reached a stream. */
    static final String OUTBOX_STREAM_NAME = "outbox";

    private final OutboxStore store;

    public OutboxEventPublisher(OutboxStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public PublishedEvent publish(DomainEvent event) {
        store.append(event);
        return new PublishedEvent(null, OUTBOX_STREAM_NAME, event);
    }
}
