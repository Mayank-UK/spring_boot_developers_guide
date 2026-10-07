package guide.domain.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The EVENT VOCABULARY — the one shape every publisher, outbox row, stream
 * entry, and consumer in this topic speaks. An event is a FACT THAT HAPPENED
 * (past tense, named so), not a command hoping someone does something:
 * {@code post.published} says the post IS published; it carries no opinion
 * about what should happen next. That discipline is what lets N unknown
 * consumers subscribe without the producer knowing any of them exist — the
 * decoupling the first section of the topic sells.
 *
 * Field by field: {@code eventId} is the DEDUPE KEY — the identity a
 * consumer's processed-set remembers so a redelivery can be recognized (the
 * exactly-once story, next section); it is minted ONCE at creation, not per
 * publish, which is precisely why an outbox row replayed three times still
 * carries the same id and can be deduped downstream. {@code aggregateType}
 * + {@code aggregateId} name the SOURCE ("post", its id) — the pair a
 * partition-keyed broker would hash to keep one aggregate's events in
 * order. {@code eventType} is the vocabulary word. {@code payload} is the
 * body (a document, deliberately not a typed hierarchy: consumers evolve
 * independently of it). {@code headers} is envelope metadata — trace and
 * correlation ids, schema version — the stuff infrastructure reads without
 * touching the body. {@code occurredAt} is when it HAPPENED, not when it
 * was delivered: those diverge by the queue's entire depth.
 *
 * Immutable record, defensive copies — an event published to N consumers
 * must never be mutable through one of them.
 */
public record DomainEvent(String eventId, String aggregateType, String aggregateId, String eventType,
        Map<String, Object> payload, Map<String, String> headers, Instant occurredAt) {

    /** The one aggregate this guide's events describe (the posts substrate). */
    public static final String AGGREGATE_POST = "post";

    /** The vocabulary word for "this post became published". */
    public static final String POST_PUBLISHED = "post.published";

    public DomainEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must be a non-blank string");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must be a non-blank string");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must be a non-blank string");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must be a non-blank string");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Map.copyOf(payload == null ? Map.of() : payload);
        headers = Map.copyOf(headers == null ? Map.of() : headers);
    }

    /**
     * The demo's canonical event: a post transitioned to published. The
     * eventId is minted here, once — the id that survives outbox replays and
     * redeliveries unchanged, which is the entire dedupe mechanism.
     */
    public static DomainEvent postPublished(String postId, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("postId", postId);
        payload.put("status", "published");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("schema", "1");
        return new DomainEvent(UUID.randomUUID().toString(), AGGREGATE_POST, postId,
                POST_PUBLISHED, payload, headers, occurredAt);
    }

    /**
     * The console's open-vocabulary factory: any eventType over any payload
     * (the stream demo publishes whatever the operator types). Same rule:
     * one eventId, minted once.
     */
    public static DomainEvent of(String eventType, String aggregateType, String aggregateId,
            Map<String, Object> payload, Instant occurredAt) {
        return new DomainEvent(UUID.randomUUID().toString(), aggregateType, aggregateId,
                eventType, payload == null ? Map.of() : payload, Map.of(), occurredAt);
    }

    /** The topic's full vocabulary, for the console's UI and validation. */
    public static List<String> vocabulary() {
        return List.of(POST_PUBLISHED, "post.title-touched", "demo.ping");
    }
}
