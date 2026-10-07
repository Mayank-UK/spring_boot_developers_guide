package guide.domain.comments;

import java.util.List;
import java.util.Map;

/**
 * The comments PORT: read-only association loading for the posts graph.
 * Same shape as PostRepository — a domain-owned interface, filled by an
 * adapter in data/comments — but deliberately TWO methods, and the pair is
 * the whole lesson of topic 13's N+1 section:
 *
 * - {@link #listForPost} answers ONE post's comments — the shape a naive
 *   per-item resolver wants, and the shape that turns "posts { comments }"
 *   into one repository round trip PER POST.
 * - {@link #listForPosts} answers MANY posts' comments in ONE call — the
 *   batch-shaped method a {@code @BatchMapping} resolver needs. It exists in
 *   the PORT (not as an adapter convenience) because batching is a contract
 *   decision: if the port cannot batch, no resolver above it can honestly
 *   fix N+1. The honest fix lives at the data edge — one SQL {@code IN}
 *   clause, one Redis MGET, one multi-get — and the port saying so is what
 *   makes the rest of the app able to rely on it.
 *
 * The returned map carries an entry for every requested id (empty list when
 * a post has no comments) so callers never re-check for absent keys.
 */
public interface CommentRepository {

    /** The comments of one post, oldest first; empty when it has none. */
    List<Comment> listForPost(String postId);

    /**
     * The comments of MANY posts in one call — the batch-shaped contract a
     * batch resolver (or DataLoader) keys its per-post answers from. Every
     * requested id is present in the answer (empty list = no comments);
     * duplicate ids collapse to one entry.
     */
    Map<String, List<Comment>> listForPosts(List<String> postIds);
}
