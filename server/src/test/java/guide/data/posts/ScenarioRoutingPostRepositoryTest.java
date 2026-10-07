package guide.data.posts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import guide.core.errors.AppException;
import guide.core.errors.FailureKind;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.sql.SchemaMigrator;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import guide.domain.posts.PostStatus;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * THE LIVE SWAP, proven end to end against the REAL application context:
 * the exposed PostRepository bean is the router; the ScenarioStore's 'api'
 * area decides at CALL TIME which adapter answers. @ServiceConnection feeds
 * the autoconfigured DataSource from a Testcontainers PostgreSQL — the exact
 * wiring production runs, container for host. (grpc moves to an ephemeral
 * port because this context cannot share the suite's cached one.)
 *
 * The story, in order: fake default has never touched PostgreSQL (no table,
 * no migration history); one programmatic flip activates live lazily
 * (migrate + seed, once) and the SAME twelve posts answer from PG; a
 * live-mode create is invisible to the fake and survives switching back and
 * forth; concurrent mode flapping never breaks a listing; and an UNREACHABLE
 * PostgreSQL at activation surfaces as the typed unavailable failure — the
 * same 503 envelope family — with the once-guard left down to retry later.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScenarioRoutingPostRepositoryTest {

    private static final String NEWEST_SEED = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private InMemoryPostRepository inMemoryPostRepository;

    @Autowired
    private ScenarioStore scenarioStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetToFakeDefaults() {
        inMemoryPostRepository.reset();
        scenarioStore.reset();
    }

    private static guide.domain.posts.PostDraft draft(String title) {
        return new PostDraft(title, "body", List.of("router-test"), PostStatus.PUBLISHED);
    }

    @Test
    @Order(1)
    void fakeByDefaultHasNeverTouchedPostgresql() {
        Integer liveTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('posts', 'flyway_schema_history')
                """, Integer.class);
        assertEquals(0, liveTables,
                "no live call yet: neither the table nor the migration history exists");
        assertEquals(12, postRepository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE)
                .totalElements(), "the fake serves the seed with PG never migrated");
    }

    @Test
    @Order(2)
    void theLiveSwapServesTheSameTwelveFromPgAndBackWithoutRestart() {
        // THE FLIP: one call-time mode change, no restart, no redeploy.
        scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.LIVE);

        PagedPosts fromPg = postRepository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(12, fromPg.totalElements(), "lazy activation migrated + seeded on first use");
        assertEquals(NEWEST_SEED, fromPg.items().getFirst().id(),
                "the same deterministic seed order, now out of PostgreSQL");

        guide.domain.posts.Post created = postRepository.create(draft("Born in live mode"));
        assertEquals(13, postRepository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE)
                .totalElements());

        // Back to fake: the in-memory adapter never saw the live-mode create.
        scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.FAKE);
        assertEquals(12, postRepository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE)
                .totalElements(), "switching back is one call, and the fake is untouched");

        // Live again: PG persisted the create; the once-guard serves without re-activating.
        scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.LIVE);
        assertEquals(13, postRepository.list(0, 100, PostSort.DEFAULT, PostFilter.NONE)
                .totalElements());
        assertTrue(postRepository.delete(created.id()), "cleanup: back to the pristine twelve");
    }

    @Test
    @Order(3)
    void concurrentModeFlippingNeverBreaksAListing() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger listings = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        try {
            CountDownLatch startGun = new CountDownLatch(1);
            List<Runnable> jobs = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final boolean flipper = t % 2 == 0;
                jobs.add(() -> {
                    try {
                        startGun.await();
                        for (int i = 0; i < 25; i++) {
                            if (flipper) {
                                scenarioStore.setDataMode(PostRepository.AREA,
                                        i % 2 == 0 ? ScenarioDataMode.LIVE : ScenarioDataMode.FAKE);
                            }
                            PagedPosts page = postRepository.list(0, 100,
                                    PostSort.DEFAULT, PostFilter.NONE);
                            if (page.totalElements() != 12) {
                                failures.incrementAndGet();
                            }
                            listings.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.incrementAndGet();
                    }
                });
            }
            startGun.countDown();
            CountDownLatch done = new CountDownLatch(threads);
            for (Runnable job : jobs) {
                pool.submit(() -> {
                    try {
                        job.run();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "every worker finished");
            assertEquals(threads * 25, listings.get(), "every listing completed");
            assertEquals(0, failures.get(),
                    "whatever the mode mid-flight, the answer is always one honest page of 12");
        } finally {
            pool.shutdownNow();
            scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.FAKE);
        }
    }

    @Test
    @Order(4)
    void unreachablePostgresAtActivationIsTheTypedUnavailableFailure() {
        // A router whose live side points at a dead port — activation must
        // surface the taxonomy's unavailable kind (503 on the wire), not a
        // driver stack trace, and must leave the once-guard DOWN to retry.
        ScenarioStore store = new ScenarioStore();
        store.setDataMode(PostRepository.AREA, ScenarioDataMode.LIVE);
        DataSource dead = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/guide", "guide", "guide");
        ScenarioRoutingPostRepository router = new ScenarioRoutingPostRepository(
                store, new InMemoryPostRepository(),
                () -> new SchemaMigrator(dead),
                () -> new PgPostRepository(new JdbcTemplate(dead)));

        AppException failure = assertThrows(AppException.class,
                () -> router.list(0, 10, PostSort.DEFAULT, PostFilter.NONE));
        assertEquals(FailureKind.UNAVAILABLE, failure.kind(),
                "dependency down at activation maps to the 503 envelope family");
        assertTrue(failure.failure().message().toLowerCase().contains("unreachable"));

        store.setDataMode(PostRepository.AREA, ScenarioDataMode.FAKE);
        assertEquals(12, router.list(0, 100, PostSort.DEFAULT, PostFilter.NONE).totalElements(),
                "fake mode never needed the dependency in the first place");
    }

    @Test
    @Order(5)
    void theAutoconfiguredPoolIsTheConfiguredHikari() {
        // Corollary of Order(2): by now the pool HAS connected (live mode ran)
        // — this pins that the DataSource behind the live adapter is the
        // autoconfigured Hikari pool, sized exactly as application.yml says.
        DataSource dataSource = jdbcTemplate.getDataSource();
        assertTrue(dataSource instanceof HikariDataSource,
                "the container-backed DataSource is Hikari, as configured");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertEquals(8, hikari.getMaximumPoolSize(), "the modest pool size from application.yml");
        assertTrue(hikari.isRunning(), "the pool started when live mode first needed it");
    }
}
