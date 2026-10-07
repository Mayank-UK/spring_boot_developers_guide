package guide.domain.messaging;

/**
 * The IDEMPOTENT CONSUMER use case: one place that turns a delivery into an
 * applied-or-skipped decision. Both doors into the pipeline go through it —
 * ConsumeFromStream (fresh and replayed reads) and ClaimIdleEntries (work
 * taken over from a dead worker) — which is the point: dedupe policy that
 * lives in one class cannot drift between the paths that need it.
 *
 * The three options for surviving duplicate delivery, and the one chosen:
 * (1) a DEDUPE KEY remembered per consumer — chosen here, because the
 * projection is a bare counter and has no natural idempotency of its own;
 * (2) NATURAL idempotency — make the projection itself duplicate-proof
 * (UPSERT a row, SET an absolute value) and skip the memory entirely;
 * (3) exactly-once infrastructure — transactional delivery+ack+process in
 * one atomic unit, which classic brokers do not offer (the myth section
 * takes it apart). The receipt this class returns ({@code duplicate}) is
 * what the demo surfaces so the difference is visible on screen.
 */
public final class IdempotentConsumer {

    /** One delivery's outcome: applied, or recognized as a redelivery. */
    public record Outcome(String id, DomainEvent event, boolean duplicate) {
    }

    private final EventCounters counters;

    public IdempotentConsumer(EventCounters counters) {
        this.counters = counters;
    }

    /** Apply (or skip) one delivery; NEVER throws — a consumer that cannot
     * process is a POISON story (delivery count grows, claim dead-letters),
     * not an exception story. */
    public Outcome process(EventStream.Delivery delivery) {
        boolean first = counters.record(delivery.event());
        return new Outcome(delivery.id(), delivery.event(), !first);
    }

    /** The projection this consumer maintains — what the demo reads. */
    public EventCounters counters() {
        return counters;
    }
}
