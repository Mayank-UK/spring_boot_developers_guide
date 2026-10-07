package guide.data.comments;

import guide.domain.comments.Comment;
import guide.domain.comments.CommentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory CommentRepository ADAPTER: a ConcurrentHashMap of immutable
 * lists, no mutation API, therefore lock-free and trivially thread-safe —
 * every read sees a fully-built, never-mutated list. (There is no write
 * side to guard; a future adapter that adds one gets to redo this math.)
 *
 * The repository also COUNTS ITS CALLS, by kind: {@link #singleLookupCount()}
 * ticks once per {@link #listForPost}, {@link #batchCallCount()} once per
 * {@link #listForPosts}. Those counters are not vanity metrics — they are
 * the topic's N+1 PROOF. A test queries posts { comments } for a page of
 * posts and asserts batchCallCount == 1 with singleLookupCount == 0: the
 * {@code @BatchMapping} resolver genuinely resolved N posts' comments in
 * ONE repository call. The naive per-item resolver (kept, dissected, and
 * deliberately NOT registered as a data fetcher in PostsGraphqlController)
 * would show the opposite: N single lookups.
 *
 * Seed: 27 comments across the twelve seed posts (two each, three on three
 * of them), deterministic ids and order, so tests and demos can address
 * them and assert order. A deleted post's comments simply stop being
 * reachable — the seed is read-only and there is no cascade to get wrong.
 */
public class InMemoryCommentRepository implements CommentRepository {

    private final Map<String, List<Comment>> commentsByPostId = new ConcurrentHashMap<>();
    private final AtomicLong singleLookups = new AtomicLong();
    private final AtomicLong batchCalls = new AtomicLong();

    public InMemoryCommentRepository() {
        SeedComments.list().forEach(this::index);
    }

    @Override
    public List<Comment> listForPost(String postId) {
        singleLookups.incrementAndGet();
        return commentsByPostId.getOrDefault(postId, List.of());
    }

    @Override
    public Map<String, List<Comment>> listForPosts(List<String> postIds) {
        batchCalls.incrementAndGet();
        Map<String, List<Comment>> byPost = new LinkedHashMap<>();
        for (String postId : postIds) {
            byPost.put(postId, commentsByPostId.getOrDefault(postId, List.of()));
        }
        return byPost;
    }

    /** Repository calls made through the single-post shape — the N+1 shape. */
    public long singleLookupCount() {
        return singleLookups.get();
    }

    /** Repository calls made through the batch shape — the N+1 fix, counted. */
    public long batchCallCount() {
        return batchCalls.get();
    }

    /** Zeroes both counters — the proof tests call this before their query. */
    public void resetCallCounts() {
        singleLookups.set(0);
        batchCalls.set(0);
    }

    private void index(Comment comment) {
        List<Comment> existing = commentsByPostId.get(comment.postId());
        if (existing == null) {
            commentsByPostId.put(comment.postId(), List.of(comment));
            return;
        }
        List<Comment> grown = new ArrayList<>(existing);
        grown.add(comment);
        commentsByPostId.put(comment.postId(), List.copyOf(grown));
    }
}
