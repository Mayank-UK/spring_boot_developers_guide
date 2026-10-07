package guide.domain.messaging;

import java.time.Instant;
import java.util.List;

/**
 * The outbox PORT: everything the relay (and the /state console) does to
 * the outbox table, in table-shaped terms. The PostgreSQL adapter behind it
 * is the one place SQL lives; the atomicity that makes the pattern work is
 * a property of the CALLER's transaction, not of this port — append() is
 * designed to ride a caller's @Transactional boundary (JdbcTemplate joins
 * the thread-bound connection), and the topic's proof test exists to pin
 * that riding.
 *
 * The claim contract is the interesting half: claimPending must hand each
 * row to AT MOST ONE concurrent caller — the adapter achieves that with
 * FOR UPDATE SKIP LOCKED (never waiting, never double-claiming) and bumps
 * attempts as a side effect, so a row's attempt count IS its crash history.
 */
public interface OutboxStore {

    /** One outbox row: the event plus its queue-state (attempts, timestamps). */
    record OutboxEntry(long id, DomainEvent event, int attempts,
            Instant createdAt, Instant processedAt) {
    }

    /** The three-column state machine, counted: pending, delivered, dead. */
    record OutboxStats(long pending, long delivered, long dead) {
    }

    /**
     * INSERT the event as an unprocessed row. Inside a caller's transaction
     * this is the dual-write FIX — same commit as the business mutation;
     * outside one it is just a durable enqueue (still useful, no longer
     * atomic with anything).
     */
    void append(DomainEvent event);

    /**
     * Claim up to {@code limit} unprocessed rows IN ARRIVAL ORDER for this
     * caller alone (SKIP LOCKED), bumping each row's attempts. Rows whose
     * attempts have already reached the relay's dead-letter threshold are
     * still claimable — the relay decides their fate, not the query.
     */
    List<OutboxEntry> claimPending(int limit);

    /**
     * Mark ids processed (delivered or dead-lettered — the row leaves the
     * queue either way; attempts distinguishes the two afterwards).
     */
    void markProcessed(List<Long> ids);

    /** The state machine, counted, for the demo's /state readout. */
    OutboxStats stats();

    /** The newest rows (processed or not), newest first, bounded. */
    List<OutboxEntry> recent(int limit);
}
