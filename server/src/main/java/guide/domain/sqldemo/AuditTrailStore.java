package guide.domain.sqldemo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The audit-trail PORT (topic 17, demo db:audit): one transactional method
 * that changes a post AND writes its audit row — one commit, both facts, or
 * neither — plus the plain reads the proof and the trail view need. The
 * AFTER-state reads exist deliberately OUTSIDE the transaction: the rollback
 * proof is only honest if the use case re-reads committed truth after the
 * transaction finished, not the in-flight view from inside it.
 *
 * The trail table itself is topic 16's V2 migration (the append-only ledger
 * this topic merely writes into — schema there, writes here).
 */
public interface AuditTrailStore {

    /** The post's current title — the before/after material of the proof. */
    Optional<String> titleOf(String postId);

    /** How many trail rows exist for one post — the number rollback must leave unchanged. */
    int trailCount(String postId);

    /**
     * Updates the post's title and appends its audit row in ONE transaction.
     * With failAfter, throws an UNTYPED crash (IllegalStateException) from
     * between the two writes on purpose: a crash is not a domain failure
     * with an HTTP status — it is exactly the thing the transaction is for,
     * and the rollback is the behavior under observation.
     */
    void transferAndAudit(String postId, String newTitle, String changedBy, boolean failAfter);

    /** The trail for one entity id, oldest first; an unknown id is simply an empty trail. */
    List<TrailEntry> trailFor(String postId);

    /** One trail row: who, when, and the payload snapshot of what changed. */
    record TrailEntry(long id, String actor, String payload, OffsetDateTime changedAt) {
    }
}
