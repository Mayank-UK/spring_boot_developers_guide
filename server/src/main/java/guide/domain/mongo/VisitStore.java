package guide.domain.mongo;

import java.util.List;

/**
 * The visits PORT: the persistence contract the domain owns and the
 * composition root fills — the exact shape PostRepository has, pointed at
 * read-pattern-shaped data instead of entity-shaped data. Two adapters
 * answer it: an in-memory fake (bounded, deterministic seed) and a MongoDB
 * adapter over MongoTemplate (Criteria queries + aggregation pipelines);
 * a scenario router picks per call, and nothing above this file knows
 * which — or that MongoDB exists at all.
 *
 * Note what the contract does NOT have: no update, no delete, no
 * optimistic-concurrency token. Visits are immutable events; the write
 * side is record() and nothing else. Designing the port around the read
 * pattern (aggregates) rather than around entities is the whole reason
 * this data lives in a document store.
 */
public interface VisitStore {

    /** Identifies this context's data adapters in the ScenarioStore's per-area mode registry. */
    String AREA = "mongo";

    /**
     * Writes one visit; the store assigns identity (id), time (occurredAt),
     * and the pseudonymized visitorHash (sha-256 of the token — the raw
     * token is never persisted). Returns the stored document.
     */
    VisitDocument record(VisitDraft draft);

    /** The newest visits first, at most {@code limit} (the caller clamps the bound). */
    List<VisitDocument> recent(int limit);

    /** Total documents stored. */
    long countAll();

    /** Documents stored for one topic; 0 when the topic has never been visited. */
    long countByTopic(String topicId);

    /** Topics by visit count, descending; count ties break on topic id ascending (deterministic). */
    List<TopicTally> topTopics(int limit);

    /**
     * Mean duration over one topic's visits (rounded half-up to ms), or over
     * every visit when {@code topicId} is null or blank; 0 when nothing
     * matches — "no average yet" is a fact the caller reports, not a failure.
     */
    long avgDurationMs(String topicId);

    /** Visits per locale, locale ascending (the histogram's deterministic order). */
    List<LocaleTally> byLocaleHistogram();
}
