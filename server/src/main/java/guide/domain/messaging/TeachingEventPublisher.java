package guide.domain.messaging;

/**
 * The publisher PORT — and the topic's central exhibit, because its TWO
 * implementations ARE the two answers to the dual-write problem:
 *
 * - DIRECT (guide.data.messaging.RedisDirectEventPublisher): XADDs to the
 *   stream the moment it is called. If the caller invokes it after a
 *   database commit, the publish sits OUTSIDE the transaction — commit then
 *   crash means the event never happens. It is the naive implementation,
 *   kept on purpose: for fire-and-forget telemetry that is legitimately
 *   good enough, and the stream console uses it because a console's events
 *   have no business mutation to atomically pair with.
 * - OUTBOX-BACKED (guide.data.messaging.OutboxEventPublisher): appends a
 *   row to the outbox table via JdbcTemplate — which, inside a caller's
 *   @Transactional method, rides the SAME transaction and the SAME commit
 *   as the business mutation. The event becomes as durable as the state
 *   change it describes; the relay delivers it later. Called outside a
 *   transaction it still works (auto-commit), but the pattern's GUARANTEE
 *   only exists when the caller brackets the mutation and this publish in
 *   one transaction.
 *
 * One port, two semantics, chosen at the COMPOSITION ROOT — the use cases
 * above stay ignorant of which one is wired, which is the port/adapter
 * payoff demonstrated rather than promised (the same lesson the posts
 * substrate taught for storage).
 */
public interface TeachingEventPublisher {

    /** Identifies this area in the ScenarioStore's per-area mode registry. */
    String AREA = "msg";

    /** What a publish produced: where the event landed, under which id. */
    record PublishedEvent(String streamId, String stream, DomainEvent event) {
    }

    /**
     * Publish one event. The contract is deliberately quiet about delivery:
     * a return means the event is SOMEWHERE durable enough for this
     * publisher's semantics (the broker accepted it · the outbox row
     * committed) — the difference between the two is the topic's story.
     */
    PublishedEvent publish(DomainEvent event);
}
