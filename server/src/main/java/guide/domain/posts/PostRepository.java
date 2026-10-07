package guide.domain.posts;

import java.util.Optional;

/**
 * The posts PORT: the persistence contract the domain owns and wiring fills.
 * The in-memory adapter answers it today; Phase 6 swaps in a PostgreSQL
 * adapter behind the SAME methods and nothing above this file notices — that
 * swap is the port/adapter pattern's payoff, demonstrated rather than promised.
 *
 * The update contract is deliberately tri-state (see {@link Update}): "missing"
 * and "stale version" are different facts that map onto different statuses
 * (404 vs 409), and a port that returned Optional&lt;Post&gt; would force the
 * caller to guess which one happened.
 */
public interface PostRepository {

    /** Identifies this context's data adapters in the ScenarioStore's per-area mode registry. */
    String AREA = "api";

    /** One filtered, sorted, paginated page of posts — pagination math done by the adapter. */
    PagedPosts list(int page, int size, PostSort sort, PostFilter filter);

    /** Exact-match lookup; empty when no post carries that id. */
    Optional<Post> findById(String id);

    /** Stores a brand-new post (identity and version assigned by the adapter); returns what was stored. */
    Post create(PostDraft draft);

    /**
     * Replaces a post's content if (and only if) expectedVersion matches the
     * stored version — optimistic concurrency: no locks held across requests,
     * the loser told exactly how stale it was.
     */
    Update update(String id, long expectedVersion, PostDraft draft);

    /** Removes a post; false when no post carried that id. */
    boolean delete(String id);

    /** Tri-state update outcome — the port cannot lie about WHY an update did not happen. */
    sealed interface Update permits Updated, NotFound, StaleVersion {
    }

    /** The stored post replaced; carries the new version. */
    record Updated(Post post) implements Update {
    }

    /** No post carries that id — the caller maps this to a 404-shaped failure. */
    record NotFound() implements Update {
    }

    /** The stored version moved on; carries the version that IS current — the caller maps this to a 409-shaped failure. */
    record StaleVersion(long currentVersion) implements Update {
    }
}
