package guide.data.sqldemo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.data.posts.PgPostRepository;
import guide.data.sql.SchemaMigrator;
import java.util.function.Supplier;

/**
 * The topic 17 demos' lazy activation — the SAME once-guarded pattern
 * ScenarioRoutingPostRepository runs for the posts area, factored out because
 * six demo adapters share it. The first db-demo call pays the schema bill
 * (SchemaMigrator.migrate() — idempotent, so "first" needs no knowledge of
 * whether it IS first) and replays the posts seed (ON CONFLICT DO NOTHING);
 * every adapter then runs against a database it can assume.
 *
 * Suppliers come from the composition root as lazy handles, so no DataSource
 * is touched until a demo actually runs — the app still boots and serves
 * with PostgreSQL unreachable (the degrade-gracefully rule), and an outage
 * at activation surfaces as the typed UNAVAILABLE failure, once per retry.
 */
public final class DbDemoActivation {

    private final Supplier<SchemaMigrator> migrator;
    private final Supplier<PgPostRepository> posts;

    private final Object monitor = new Object();
    private volatile boolean activated;

    public DbDemoActivation(Supplier<SchemaMigrator> migrator, Supplier<PgPostRepository> posts) {
        this.migrator = migrator;
        this.posts = posts;
    }

    /** Idempotent readiness: migrate + seed on first call, nothing on the rest. */
    public void ensureReady() {
        if (!activated) {
            synchronized (monitor) {
                if (!activated) {
                    Result<SchemaMigrator.MigrationSummary> migrated = migrator.get().migrate();
                    if (migrated instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
                        throw AppException.of(failed.failure());
                    }
                    posts.get().ensureSeeded();
                    // Set only after BOTH steps succeeded — a failed activation
                    // stays down so the retry pays it once more, successfully.
                    activated = true;
                }
            }
        }
    }
}
