package guide.data.posts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import guide.domain.posts.PostStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * InMemoryPostRepository contract: a deterministic seed (stable ids, stable
 * order — tests address seeds by id), correct pagination math (totalPages,
 * the last short page, beyond-range pages), filtering and sorting through the
 * typed filter/sort values, optimistic updates that actually enforce the
 * version, and thread safety under concurrent mutation (reads never lock,
 * exactly one of two racing writers wins).
 */
class InMemoryPostRepositoryTest {

    private static final String NEWEST_SEED = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";
    private static final String REVISED_LATER = "5b481618-f93a-4b46-572d-280949083b05";
    private static final String TITLE_FIRST_ASC = "7d2a3830-1b5c-4d68-790f-4a2b6c0a5d03";

    private final InMemoryPostRepository repository = new InMemoryPostRepository();

    private static PostDraft draft(String title) {
        return new PostDraft(title, "body of " + title, List.of("test"), PostStatus.PUBLISHED);
    }

    @Test
    void seedIsDeterministicIdsAndOrderStableAcrossInstances() {
        PagedPosts first = repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE);
        PagedPosts second = new InMemoryPostRepository().list(0, 100, PostSort.DEFAULT, PostFilter.NONE);

        assertEquals(12, first.totalElements(), "twelve seed posts");
        assertEquals(12, SeedPosts.list().size());
        assertEquals(NEWEST_SEED, first.items().getFirst().id(),
                "default order is createdAt descending — newest seed first");
        assertEquals(first.items().stream().map(Post::id).toList(),
                second.items().stream().map(Post::id).toList(),
                "two fresh instances answer identically (tests may address seeds by id)");
    }

    @Test
    void paginationMathTotalPagesAndShortLastPage() {
        assertEquals(3, repository.list(0, 5, PostSort.DEFAULT, PostFilter.NONE).totalPages());
        assertEquals(2, repository.list(0, 10, PostSort.DEFAULT, PostFilter.NONE).totalPages());

        PagedPosts lastShortPage = repository.list(2, 5, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(2, lastShortPage.items().size(), "12 items at size 5: page 2 carries the remainder");
        assertTrue(lastShortPage.isLast());

        PagedPosts beyondRange = repository.list(99, 10, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(0, beyondRange.items().size());
        assertEquals(12, beyondRange.totalElements(), "out-of-range page: empty items, honest totals");
        assertEquals(2, beyondRange.totalPages());
    }

    @Test
    void filteringByStatusAndTagAndTheirIntersection() {
        assertEquals(2, repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of("draft", null))).totalElements());
        assertEquals(2, repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of(null, "rest"))).totalElements());
        assertEquals(3, repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of("published", "http"))).totalElements());

        PagedPosts none = repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of(null, "no-such-tag")));
        assertEquals(0, none.totalElements());
        assertEquals(0, none.totalPages());
        assertTrue(none.items().isEmpty());
    }

    @Test
    void sortingByTitleAndByUpdatedAtUsesTheTypedSortWithIdTieBreak() {
        List<Post> byTitle = repository.list(0, 100,
                value(PostSort.parse("title,asc")), PostFilter.NONE).items();
        assertEquals(TITLE_FIRST_ASC, byTitle.getFirst().id(),
                "case-insensitive title order puts the digit-led title first");

        List<Post> byUpdatedAt = repository.list(0, 100,
                value(PostSort.parse("updatedAt,desc")), PostFilter.NONE).items();
        assertEquals(NEWEST_SEED, byUpdatedAt.getFirst().id());
        assertEquals(REVISED_LATER, byUpdatedAt.get(2).id(),
                "the revised seed (later updatedAt) climbs above its createdAt neighbors");
        assertNotEquals(byTitle.stream().map(Post::id).toList().indexOf(REVISED_LATER),
                byUpdatedAt.stream().map(Post::id).toList().indexOf(REVISED_LATER),
                "updatedAt order genuinely differs from title order");
    }

    @Test
    void createMintsIdentityVersionOneAndAnswersFindImmediately() {
        Post created = repository.create(draft("A brand new post"));

        assertNotNull(UUID.fromString(created.id()), "identity is a UUID");
        assertEquals(1, created.version());
        assertEquals(created, repository.findById(created.id()).orElseThrow());
        assertEquals(13, repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements());
    }

    @Test
    void optimisticUpdateEnforcesTheVersionExactly() {
        Post created = repository.create(draft("Versioned"));

        PostRepository.Update stale = repository.update(created.id(), 99, draft("Nope"));
        assertTrue(stale instanceof PostRepository.StaleVersion);
        assertEquals(1, ((PostRepository.StaleVersion) stale).currentVersion());
        assertEquals(1, repository.findById(created.id()).orElseThrow().version(),
                "a stale write changes nothing");

        PostRepository.Update ok = repository.update(created.id(), 1, draft("Yes"));
        assertTrue(ok instanceof PostRepository.Updated);
        Post updated = ((PostRepository.Updated) ok).post();
        assertEquals(2, updated.version());
        assertEquals(created.createdAt(), updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(created.updatedAt()));

        assertTrue(repository.update("missing", 1, draft("x")) instanceof PostRepository.NotFound);
    }

    @Test
    void deleteIsAtomicAndRepeatable() {
        Post created = repository.create(draft("Doomed"));

        assertTrue(repository.delete(created.id()));
        assertTrue(repository.findById(created.id()).isEmpty());
        assertFalse(repository.delete(created.id()), "second delete: nothing there");
        assertFalse(repository.delete(null));
    }

    @Test
    void concurrentRacingWritersProduceExactlyOneWinner() throws Exception {
        Post created = repository.create(draft("The contested post"));
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch startGun = new CountDownLatch(1);
            List<Future<PostRepository.Update>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final String writerTitle = "Writer " + i;
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return repository.update(created.id(), 1, draft(writerTitle));
                }));
            }
            startGun.countDown();

            int updated = 0;
            int stale = 0;
            for (Future<PostRepository.Update> future : futures) {
                if (future.get(5, TimeUnit.SECONDS) instanceof PostRepository.Updated) {
                    updated++;
                } else {
                    stale++;
                }
            }
            assertEquals(1, updated, "exactly one writer wins the version race");
            assertEquals(writers - 1, stale);
            assertEquals(2, repository.findById(created.id()).orElseThrow().version());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resetRestoresTheDeterministicSeed() {
        repository.create(draft("Transient"));
        repository.delete(NEWEST_SEED);
        assertEquals(12, repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements());

        repository.reset();
        PagedPosts after = repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(12, after.totalElements(), "reset re-seeds the original twelve");
        assertEquals(NEWEST_SEED, after.items().getFirst().id());
        assertTrue(repository.findById(NEWEST_SEED).isPresent());
    }

    /** PostSort.parse/PostFilter.of return Results on this test's happy paths — unwrap once. */
    private static <T> T value(guide.core.result.Result<T> result) {
        return ((guide.core.result.Success<T>) result).value();
    }
}
