package guide.domain.messaging;

import javax.annotation.Nullable;

/**
 * The transactional-mutation PORT: ONE method whose adapter implementation
 * is @Transactional and performs the posts mutation (publish the post)
 * plus — when the caller passes an in-transaction publisher — the event
 * append, on the SAME connection inside the SAME commit. This is the seam
 * the whole outbox proof hangs on: the USE CASE decides policy (which
 * publisher, where to crash), the ADAPTER owns the transaction bracket,
 * and the database makes the two writes atomic.
 *
 * The in-transaction publisher parameter is the dual-write lesson as a
 * signature: pass the outbox publisher and the event rides the commit;
 * pass null and the transaction contains only the mutation — the direct
 * publisher then fires AFTER commit, outside any transaction, in the
 * window where a crash loses the event. Same method, two semantics, one
 * visible line of difference.
 */
public interface PostEventTransaction {

    /** What the transaction did: the post it published, and the event id. */
    record PostMutation(String postId, String title, String eventId, long newVersion) {
    }

    /**
     * Publish the post (draft→published, version+1) and — when
     * {@code inTransactionPublisher} is present — append the event in the
     * same transaction. {@code failBeforeCommit} throws INSIDE the method
     * (between the mutation and the append), so the proxy rolls back
     * everything: the crash-mid-transaction story.
     */
    PostMutation transactPublish(String postId, DomainEvent event, boolean failBeforeCommit,
            @Nullable TeachingEventPublisher inTransactionPublisher);
}
