package guide.data.posts;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.sql.SchemaMigrator;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The LIVE SWAP: one PostRepository bean that answers every call from
 * whichever adapter the ScenarioStore's 'api' area points at AT CALL TIME —
 * PATCH /api/demo/scenario {"dataMode":"live"} and the very next request
 * reads PostgreSQL; PATCH it back and the next request reads the in-memory
 * seed. No restart, no redeploy: the storage swap the port promised.
 *
 * The live adapter activates LAZILY, exactly once: the first live call runs
 * SchemaMigrator.migrate() + PgPostRepository.ensureSeeded() under a monitor
 * (double-checked on a volatile flag), then every later call reuses the
 * activated adapter. Activation failure — PostgreSQL unreachable — throws
 * the taxonomy's typed unavailable exception, so use cases and controllers
 * render the same 503 envelope the failure-injection exhibit serves; the
 * flag stays DOWN, so a retry after the dependency heals pays the activation
 * once more, successfully. Nothing here can NPE on a partial state: the
 * adapter field is written only fully-initialized, and only after activation
 * succeeded.
 *
 * The two suppliers come from the composition root as ObjectProviders, so
 * contexts without a DataSource (controller slices) never construct the
 * SQL-side beans at all — this router is the only PostRepository bean eager
 * contexts pay for.
 */
public final class ScenarioRoutingPostRepository implements PostRepository {

    private final ScenarioStore scenarioStore;
    private final InMemoryPostRepository fake;
    private final Supplier<SchemaMigrator> schemaMigrator;
    private final Supplier<PgPostRepository> liveAdapter;

    private final Object activationMonitor = new Object();
    private volatile boolean liveActivated;
    private volatile PgPostRepository activatedLive;

    public ScenarioRoutingPostRepository(ScenarioStore scenarioStore,
            InMemoryPostRepository fake,
            Supplier<SchemaMigrator> schemaMigrator,
            Supplier<PgPostRepository> liveAdapter) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.fake = Objects.requireNonNull(fake, "fake");
        this.schemaMigrator = Objects.requireNonNull(schemaMigrator, "schemaMigrator");
        this.liveAdapter = Objects.requireNonNull(liveAdapter, "liveAdapter");
    }

    /** The adapter this call goes to — read at CALL time, which is the whole point. */
    private PostRepository delegate() {
        return scenarioStore.dataModeFor(AREA) == ScenarioDataMode.LIVE
                ? liveActivated()
                : fake;
    }

    /** The once-guarded lazy activation: migrate + seed on first live use. */
    private PostRepository liveActivated() {
        if (!liveActivated) {
            synchronized (activationMonitor) {
                if (!liveActivated) {
                    SchemaMigrator migrator = schemaMigrator.get();
                    Result<SchemaMigrator.MigrationSummary> migrated = migrator.migrate();
                    if (migrated instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
                        throw AppException.of(failed.failure());
                    }
                    PgPostRepository pg = liveAdapter.get();
                    pg.ensureSeeded();
                    // Fully initialized BEFORE the flag flips — callers that
                    // passed the first check see a complete adapter or none.
                    activatedLive = pg;
                    liveActivated = true;
                }
            }
        }
        return activatedLive;
    }

    @Override
    public PagedPosts list(int page, int size, PostSort sort, PostFilter filter) {
        return delegate().list(page, size, sort, filter);
    }

    @Override
    public Optional<Post> findById(String id) {
        return delegate().findById(id);
    }

    @Override
    public Post create(PostDraft draft) {
        return delegate().create(draft);
    }

    @Override
    public Update update(String id, long expectedVersion, PostDraft draft) {
        return delegate().update(id, expectedVersion, draft);
    }

    @Override
    public boolean delete(String id) {
        return delegate().delete(id);
    }

    /** The fake adapter this router serves in fake mode (tests and demos assert against it). */
    public InMemoryPostRepository fakeAdapter() {
        return fake;
    }
}
