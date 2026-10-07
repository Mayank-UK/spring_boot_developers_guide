package guide.domain.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The stream-console PORT: the consumer-group surface in domain terms —
 * append, read (new or own-pending), ack, pending inspection, claim, and
 * the dead-letter queue, over one stream and one consumer group. The
 * adapter (guide.data.messaging.RedisStreamMessaging) maps each method to
 * its Redis command (XADD, XREADGROUP, XACK, XPENDING, XCLAIM, XRANGE) and
 * owns the key namespace — nothing above this file knows a stream key.
 *
 * This is deliberately a LOW-LEVEL port (records shaped like the protocol's
 * answers) rather than a "queue" abstraction: the topic's whole point is
 * teaching what consumer groups actually DO, and hiding the machinery
 * behind a generic Queue interface would bury the lesson. The use cases
 * above it (consume, claim, inspect) are where policy lives; this port is
 * where mechanics live.
 */
public interface EventStream {

    /** The group's name, decided here so use cases and prose agree. */
    String GROUP = "guide-demo";

    /** The steady consumer the console reads with (worker-1 in the demo). */
    String CONSUMER = "worker-1";

    /** The takeover consumer claims land on (worker-2 — the second worker). */
    String CLAIM_CONSUMER = "worker-2";

    /** One delivered entry: its stream id, its event, and who holds it. */
    record Delivery(String id, DomainEvent event, String consumer, long deliveryCount) {
    }

    /** The XPENDING answer: totals, per-consumer breakdown, and entries. */
    record PendingView(long total, String minId, String maxId,
            Map<String, Long> perConsumer, List<PendingEntry> entries) {
    }

    /** One pending entry: id, holder, idle time, and how often delivered. */
    record PendingEntry(String id, String consumer, long idleMs, long deliveryCount) {
    }

    /** What a claim did: entries taken over (with post-claim delivery
     * counts) split into reprocessable and already-poison. */
    record ClaimedEntries(List<Delivery> reprocessable, List<Delivery> poison,
            String claimedBy, long minIdleMs) {
    }

    /** One dead-lettered entry, as the DLQ stream stored it. */
    record DeadLetter(String id, String eventId, String eventType, String reason,
            String deadAt, DomainEvent event) {
    }

    /** XADD: append one event; answers the stream id the broker assigned. */
    TeachingEventPublisher.PublishedEvent append(DomainEvent event);

    /** XADD to the dead-letter stream: park an entry for human eyes. */
    String appendDead(DomainEvent event, String reason);

    /** Idempotent group creation — the activation path calls it once. */
    void ensureGroup();

    /**
     * XREADGROUP: deliver up to {@code count} entries to {@code consumer}.
     * {@code replayPending} reads THIS consumer's un-acked entries (offset
     * "0") instead of new ones (offset ">") — the crash-recovery read.
     */
    List<Delivery> readGroup(String consumer, int count, boolean replayPending);

    /** XACK: acknowledge ids; answers how many left the pending list. */
    long ack(List<String> ids);

    /** XPENDING (summary + entries): everything delivered and not acked. */
    PendingView pending();

    /**
     * The visibility-timeout takeover: XPENDING filtered by idle + XCLAIM.
     * Entries idle at least {@code minIdle} move to {@code consumer} with
     * their delivery counts (post-claim: each claim is one more delivery);
     * the caller splits them into reprocessable and poison.
     */
    ClaimedEntries claimIdle(String consumer, Duration minIdle);

    /** XRANGE over the dead-letter stream, newest last, bounded. */
    List<DeadLetter> deadLetters(int limit);
}
