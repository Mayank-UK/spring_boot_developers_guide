package guide.data.schema;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.sql.SchemaMigrator;
import guide.domain.schema.SchemaCatalog;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The 'db' area's lazy LIVE ACTIVATION — the schema demos' counterpart of
 * ScenarioRoutingPostRepository's once-guard, in miniature. The first schema
 * call runs SchemaMigrator.migrate() under a monitor (double-checked on a
 * volatile flag); every later call reuses the activation. Activation failure
 * (PostgreSQL unreachable, SQLSTATE class 08) throws the taxonomy's typed
 * unavailable exception — the same 503 envelope family every other degraded
 * dependency serves — and leaves the once-guard DOWN so a retry after the
 * dependency heals pays the activation once more, successfully.
 *
 * The ScenarioStore's per-area registry is kept honest alongside: the config
 * registers the 'db' area FAKE ("not yet activated" — there is no in-memory
 * schema fake to route to), and THIS class flips the area LIVE the moment
 * migrations have actually run. That flip is the programmatic activation
 * seam: the public PATCH /api/demo/scenario moves only the GLOBAL mode, so
 * per-area activation is a call the data layer makes itself, deliberately.
 */
public final class LiveDbArea {

    private final ScenarioStore scenarioStore;
    private final Supplier<SchemaMigrator> schemaMigrator;

    private final Object activationMonitor = new Object();
    private volatile boolean liveActivated;

    public LiveDbArea(ScenarioStore scenarioStore, Supplier<SchemaMigrator> schemaMigrator) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.schemaMigrator = Objects.requireNonNull(schemaMigrator, "schemaMigrator");
    }

    /**
     * Idempotent activate-once: migrate on first call, flip the store's 'db'
     * area LIVE on success. Every adapter method calls this before touching
     * the database, so nothing above the adapter layer knows activation exists.
     */
    public void ensureActivated() {
        if (liveActivated) {
            return;
        }
        synchronized (activationMonitor) {
            if (liveActivated) {
                return;
            }
            Result<SchemaMigrator.MigrationSummary> migrated = schemaMigrator.get().migrate();
            if (migrated instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
                throw AppException.of(failed.failure());
            }
            // Fully activated BEFORE the flag flips — concurrent readers see
            // either a migrated schema or a pending activation, never a half state.
            scenarioStore.setDataMode(SchemaCatalog.AREA, ScenarioDataMode.LIVE);
            liveActivated = true;
        }
    }
}
