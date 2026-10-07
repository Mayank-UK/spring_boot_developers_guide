package guide.data.posts;

import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PostRepository ADAPTER: a ConcurrentHashMap for lock-free reads,
 * one monitor for every mutation. The design splits on how the data is used —
 * reads snapshot the map's values (weakly consistent, always lock-free),
 * while create/update/delete synchronize so the check-then-act in update
 * (exists? version matches? then replace) is atomic. Optimistic concurrency
 * means no lock is ever HELD across requests: the version field in each Post
 * IS the lock, and losing writers learn how stale they were from the outcome.
 *
 * This is the adapter Phase 6 replaces with PostgreSQL behind the same port —
 * fake now, live later, nothing above guide.domain.posts changes in between.
 * reset() restores the deterministic seed (tests and demos rely on it).
 */
public class InMemoryPostRepository implements PostRepository {

    private final Map<String, Post> postsById = new ConcurrentHashMap<>();
    private final List<Post> seed;
    private final Object mutationMonitor = new Object();

    public InMemoryPostRepository() {
        this(SeedPosts.list());
    }

    public InMemoryPostRepository(List<Post> seed) {
        this.seed = List.copyOf(seed);
        seed.forEach(post -> postsById.put(post.id(), post));
    }

    @Override
    public PagedPosts list(int page, int size, PostSort sort, PostFilter filter) {
        List<Post> matching = postsById.values().stream()
                .filter(filter::matches)
                .sorted(sort.comparator())
                .toList();
        long totalElements = matching.size();
        long from = (long) page * size;
        List<Post> items = from >= totalElements
                ? List.of()
                : matching.subList((int) from, (int) Math.min(from + size, totalElements));
        return PagedPosts.of(items, page, size, totalElements);
    }

    @Override
    public Optional<Post> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(postsById.get(id));
    }

    @Override
    public Post create(PostDraft draft) {
        synchronized (mutationMonitor) {
            Post post = Post.initial(UUID.randomUUID().toString(), draft, Instant.now());
            postsById.put(post.id(), post);
            return post;
        }
    }

    @Override
    public Update update(String id, long expectedVersion, PostDraft draft) {
        synchronized (mutationMonitor) {
            Post current = id == null ? null : postsById.get(id);
            if (current == null) {
                return new NotFound();
            }
            if (current.version() != expectedVersion) {
                return new StaleVersion(current.version());
            }
            Post revised = current.revised(draft, Instant.now());
            postsById.put(id, revised);
            return new Updated(revised);
        }
    }

    @Override
    public boolean delete(String id) {
        if (id == null) {
            return false;
        }
        synchronized (mutationMonitor) {
            return postsById.remove(id) != null;
        }
    }

    /** Restores the deterministic seed — what every boot starts from, and tests re-start from. */
    public void reset() {
        synchronized (mutationMonitor) {
            postsById.clear();
            seed.forEach(post -> postsById.put(post.id(), post));
        }
    }
}
