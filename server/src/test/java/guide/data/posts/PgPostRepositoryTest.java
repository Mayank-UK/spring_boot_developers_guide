package guide.data.posts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.sql.SchemaMigrator;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import guide.domain.posts.PostStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PgPostRepository against REAL PostgreSQL (Testcontainers, postgres:17):
 * the SAME port contract InMemoryPostRepositoryTest pins, plus the parity
 * proof that matters most — fake and live adapters answer IDENTICAL id
 * sequences for every sort/filter/pagination the API exposes, because the
 * HTTP surface must not drift one byte when the storage swaps (Phase 6's
 * whole thesis). Docker is REQUIRED: no reachable Docker environment fails
 * these tests loudly rather than skipping green.
 *
 * Optimistic concurrency is enforced by the UPDATE's WHERE clause, so the
 * race test is a REAL race: eight concurrent writers quoting version 1 —
 * the database, not a Java monitor, must let exactly one through.
 */
@Testcontainers
class PgPostRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static final String NEWEST_SEED = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";
    private static final String REVISED_LATER = "5b481618-f93a-4b46-572d-280949083b05";
    private static final String TITLE_FIRST_ASC = "7d2a3830-1b5c-4d68-790f-4a2b6c0a5d03";

    /** The contract oracle: the fake's answers are the API's published behavior. */
    private static final InMemoryPostRepository FAKE = new InMemoryPostRepository();

    private static PgPostRepository repository;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSeedAndBuild() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertTrue(new SchemaMigrator(dataSource).migrate() instanceof Success,
                "V1 migrates before the adapter is exercised");
        jdbc = new JdbcTemplate(dataSource);
        repository = new PgPostRepository(jdbc);
    }

    /**
     * Full reset per test — order-independent even if a test fails mid-way
     * and skips its own cleanup: wipe, then replay the idempotent seed.
     * (This IS the seeding model the adapter runs in production: any fresh
     * or dirty database converges to seed + whatever live edits exist.)
     */
    @BeforeEach
    void wipeAndReseed() {
        jdbc.update("DELETE FROM posts");
        repository.ensureSeeded();
    }

    /**
     * PostgreSQL timestamptz carries MICROSECOND precision; Java Instants
     * carry nanoseconds — a live round-trip truncates the tail. Assertions
     * compare at the resolution the database actually stores (an honest
     * property of the adapter, pinned here rather than papered over).
     */
    private static java.time.Instant micros(java.time.Instant instant) {
        return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static PostDraft draft(String title) {
        return new PostDraft(title, "body of " + title, List.of("test"), PostStatus.PUBLISHED);
    }

    // ---- the parity proof: fake and live answer identically ----

    @Test
    void everySortFilterAndPageAnswersTheSameIdsAsTheFakeAdapter() {
        assertSameIdsAsFake("createdAt desc (the default listing)",
                0, 100, PostSort.DEFAULT, PostFilter.NONE);
        assertSameIdsAsFake("title,asc (case-insensitive, id tie-break)",
                0, 100, value(PostSort.parse("title,asc")), PostFilter.NONE);
        assertSameIdsAsFake("title,desc",
                0, 100, value(PostSort.parse("title,desc")), PostFilter.NONE);
        assertSameIdsAsFake("updatedAt,desc (differs from createdAt order)",
                0, 100, value(PostSort.parse("updatedAt,desc")), PostFilter.NONE);
        assertSameIdsAsFake("updatedAt,asc",
                0, 100, value(PostSort.parse("updatedAt,asc")), PostFilter.NONE);
        assertSameIdsAsFake("status=published tag=http intersection",
                0, 100, PostSort.DEFAULT, value(PostFilter.of("published", "http")));
        assertSameIdsAsFake("status=draft",
                0, 100, PostSort.DEFAULT, value(PostFilter.of("draft", null)));
        assertSameIdsAsFake("a middle page at size 5, title,asc",
                1, 5, value(PostSort.parse("title,asc")), PostFilter.NONE);
        assertSameIdsAsFake("a beyond-range page serves empty items with honest totals",
                99, 10, PostSort.DEFAULT, PostFilter.NONE);
    }

    private static void assertSameIdsAsFake(String what, int page, int size,
            PostSort sort, PostFilter filter) {
        List<String> fakeIds = FAKE.list(page, size, sort, filter).items().stream()
                .map(Post::id).toList();
        PagedPosts live = repository.list(page, size, sort, filter);
        assertEquals(fakeIds, live.items().stream().map(Post::id).toList(),
                "fake and live agree on " + what);
        assertEquals(FAKE.list(page, size, sort, filter).totalElements(), live.totalElements(),
                "totals agree for " + what);
    }

    // ---- the listing contract, pinned directly ----

    @Test
    void seedIsDeterministicNewestFirstWithHonestPagingMath() {
        PagedPosts all = repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(12, all.totalElements());
        assertEquals(NEWEST_SEED, all.items().getFirst().id(), "default order is createdAt desc");
        assertEquals(REVISED_LATER, repository.list(0, 100,
                        value(PostSort.parse("updatedAt,desc")), PostFilter.NONE)
                        .items().get(2).id(), "the revised seed climbs by updatedAt");

        assertEquals(3, repository.list(0, 5, PostSort.DEFAULT, PostFilter.NONE).totalPages());
        PagedPosts lastShortPage = repository.list(2, 5, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(2, lastShortPage.items().size(), "12 items at size 5: page 2 carries the remainder");
        assertTrue(lastShortPage.isLast());

        PagedPosts beyondRange = repository.list(99, 10, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(0, beyondRange.items().size());
        assertEquals(12, beyondRange.totalElements(),
                "the CTE keeps the total honest even when LIMIT returns nothing");
    }

    @Test
    void filteringByStatusTagAndNoMatchTag() {
        assertEquals(2, repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of("draft", null))).totalElements());
        assertEquals(2, repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of(null, "rest"))).totalElements());
        PagedPosts none = repository.list(0, 100, PostSort.DEFAULT,
                value(PostFilter.of(null, "no-such-tag")));
        assertEquals(0, none.totalElements());
        assertTrue(none.items().isEmpty());
    }

    // ---- identity, reads, and the hostile-id paths ----

    @Test
    void createMintsAUuidAtVersionOneAndRoundTripsThroughFind() {
        Post created = repository.create(draft("A brand new post"));
        Post found = repository.findById(created.id()).orElseThrow();

        assertTrue(UUID.fromString(created.id()) != null, "identity is a UUID");
        assertEquals(1, created.version());
        assertEquals(created.id(), found.id());
        assertEquals(created.title(), found.title());
        assertEquals(created.tags(), found.tags());
        assertEquals(created.status(), found.status());
        assertEquals(created.version(), found.version());
        assertEquals(micros(created.createdAt()), micros(found.createdAt()),
                "timestamps round-trip at PG's microsecond resolution");
        assertEquals(micros(created.updatedAt()), micros(found.updatedAt()));
        assertEquals(13, repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements());

        assertTrue(repository.delete(created.id()), "cleanup: the created post");
    }

    @Test
    void unknownAndNonUuidIdsAreEmptyNeverDriverErrors() {
        assertTrue(repository.findById("00000000-0000-0000-0000-000000000000").isEmpty());
        assertTrue(repository.findById("does-not-exist").isEmpty(),
                "a non-UUID id is simply not found — the driver never sees it as SQL");
        assertTrue(repository.findById(null).isEmpty());
    }

    // ---- optimistic concurrency: enforced in the UPDATE, classified after ----

    @Test
    void updateMapsTheTriStateNotFoundStaleUpdated() {
        Post created = repository.create(draft("Versioned"));

        assertTrue(repository.update("ffffffff-ffff-ffff-ffff-ffffffffffff", 1, draft("Ghost"))
                instanceof PostRepository.NotFound);

        PostRepository.Update stale = repository.update(created.id(), 99, draft("Nope"));
        assertTrue(stale instanceof PostRepository.StaleVersion);
        assertEquals(1, ((PostRepository.StaleVersion) stale).currentVersion());
        assertEquals(1, repository.findById(created.id()).orElseThrow().version(),
                "a stale write changes nothing");

        PostRepository.Update ok = repository.update(created.id(), 1, draft("Yes"));
        assertTrue(ok instanceof PostRepository.Updated);
        Post updated = ((PostRepository.Updated) ok).post();
        assertEquals(2, updated.version(), "version = old + 1, computed by the UPDATE itself");
        assertEquals(micros(created.createdAt()), micros(updated.createdAt()), "createdAt never moves");
        assertTrue(!micros(updated.updatedAt()).isBefore(micros(created.updatedAt())),
                "updatedAt moves forward (at PG's microsecond resolution)");
        assertEquals("Yes", repository.findById(created.id()).orElseThrow().title(),
                "the stored content is the winner's");

        assertTrue(repository.delete(created.id()), "cleanup: the versioned post");
    }

    @Test
    void eightConcurrentWritersQuotingV1ProduceExactlyOneWinner() throws Exception {
        Post created = repository.create(draft("The contested post"));
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch startGun = new CountDownLatch(1);
            AtomicInteger updatedCount = new AtomicInteger();
            List<Future<PostRepository.Update>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final String writerTitle = "Writer " + i;
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return repository.update(created.id(), 1, draft(writerTitle));
                }));
            }
            startGun.countDown();
            for (Future<PostRepository.Update> future : futures) {
                if (future.get(10, TimeUnit.SECONDS) instanceof PostRepository.Updated) {
                    updatedCount.incrementAndGet();
                }
            }
            assertEquals(1, updatedCount.get(),
                    "the WHERE version = ? clause lets exactly ONE writer through — no Java lock");
            assertEquals(2, repository.findById(created.id()).orElseThrow().version());
        } finally {
            pool.shutdownNow();
            repository.delete(created.id());
        }
    }

    // ---- deletion and idempotent seeding ----

    @Test
    void deleteIsTrueThenFalseAndANonUuidIdIsFalse() {
        Post created = repository.create(draft("Doomed"));
        assertTrue(repository.delete(created.id()));
        assertTrue(repository.findById(created.id()).isEmpty());
        assertFalse(repository.delete(created.id()), "second delete: nothing there");
        assertFalse(repository.delete("not-a-uuid"));
    }

    @Test
    void ensureSeededIsIdempotentAndNeverClobbersLiveEdits() {
        Post created = repository.create(draft("Survives re-seeding"));
        assertEquals(13, repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements());

        repository.ensureSeeded();
        repository.ensureSeeded();

        assertEquals(13, repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements(),
                "ON CONFLICT (id) DO NOTHING: replays neither duplicate nor overwrite");
        assertEquals("Survives re-seeding",
                repository.findById(created.id()).orElseThrow().title(),
                "a live-mode edit survives re-activation — seeding never clobbers");
        assertTrue(repository.delete(created.id()), "cleanup: the created post");
        assertTrue(repository.delete(NEWEST_SEED), "delete a SEED row too");
        assertEquals(11, repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements());

        repository.ensureSeeded();
        PagedPosts restored = repository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(12, restored.totalElements(),
                "a deleted seed row comes back on the next replay — seed data is a floor, not a cage");
        assertTrue(repository.findById(NEWEST_SEED).isPresent());
        assertEquals(NEWEST_SEED, restored.items().getFirst().id());
    }

    private static <T> T value(Result<T> result) {
        return ((Success<T>) result).value();
    }
}
