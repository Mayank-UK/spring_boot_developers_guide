package guide.domain.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The consumer's PROJECTION plus its DEDUPE MEMORY — the two halves of
 * "exactly-once, the achievable version".
 *
 * The projection: per-eventType counters, the demo's window into what the
 * consumer did. A real consumer's projection would be a table (a read
 * model); an in-memory map is the same idea with the storage swapped, and
 * the swap is not the lesson.
 *
 * The dedupe memory: a BOUNDED set of event ids this consumer has already
 * applied, consulted before any projection write. On a redelivery (at
 * -least-once delivery makes them routine) the id is already there, the
 * projection is skipped, and the delivery is acked — from the outside,
 * once. Bounded is the honest word: memory is finite, so the set keeps the
 * newest N ids (access-ordered LRU). A duplicate arriving after its id has
 * aged out WILL be re-applied — the standard mitigation is moving the set
 * to the database (a processed_events table keyed by eventId, pruned on the
 * same cadence as the outbox) or relying on NATURAL idempotency (upserts,
 * absolute sets), both taught in the topic's eighth section. The window
 * size is a retention decision, not a correctness guarantee — the topic
 * says so out loud.
 */
public final class EventCounters {

    /** The dedupe window: newest 1024 event ids (an LRU, access-ordered). */
    static final int PROCESSED_WINDOW = 1024;

    private final Map<String, Boolean> processedIds =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private long duplicates;

    /**
     * Record one delivery: true the FIRST time this eventId is applied
     * (counter incremented), false on a redelivery (nothing moved, the
     * duplicate tally noted). Synchronized because the relay tick and HTTP
     * consume calls can race — cheap enough for a demo, and the honest
     * answer for a shared projection.
     */
    public synchronized boolean record(DomainEvent event) {
        if (processedIds.containsKey(event.eventId())) {
            duplicates++;
            return false;
        }
        processedIds.put(event.eventId(), Boolean.TRUE);
        if (processedIds.size() > PROCESSED_WINDOW) {
            var eldest = processedIds.keySet().iterator().next();
            processedIds.remove(eldest);
        }
        counts.merge(event.eventType(), 1L, Long::sum);
        return true;
    }

    /** How many times this eventType has been APPLIED (duplicates excluded). */
    public synchronized long countFor(String eventType) {
        return counts.getOrDefault(eventType, 0L);
    }

    /** The projection snapshot: eventType → applied count. */
    public synchronized Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>(counts);
        for (String known : DomainEvent.vocabulary()) {
            copy.putIfAbsent(known, 0L);
        }
        return copy;
    }

    /** Redeliveries recognized and skipped — the idempotency receipt. */
    public synchronized long duplicates() {
        return duplicates;
    }

    /** Current dedupe-window occupancy (bounded by the window size). */
    public synchronized int processedSetSize() {
        return processedIds.size();
    }

    /** Test/demo handle: forget everything (not a production operation). */
    public synchronized void reset() {
        processedIds.clear();
        counts.clear();
        duplicates = 0;
    }
}
