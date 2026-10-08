package guide.data.mongo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.mongo.VisitStore;
import java.util.Objects;

/**
 * The 'mongo' area's lazy LIVE ACTIVATION — LiveDbArea's exact shape, one
 * store over. The first live-mode call activates under a monitor
 * (double-checked on a volatile flag): ping the server, create the indexes,
 * and only then flip the ScenarioStore's 'mongo' area LIVE — the
 * programmatic activation seam, because the public PATCH endpoint moves
 * only the GLOBAL mode. Activation failure (MongoDB unreachable) is a
 * typed unavailable failure the two entry points expose in the two shapes
 * callers need: {@link #ensureActivated()} throws the taxonomy's typed
 * exception (the 503 envelope every degraded dependency serves), while
 * {@link #activate()} returns the Failed Result so the demo's /mode
 * endpoint can answer 200 with an honest hint instead of an error. The
 * flag stays DOWN on failure, so a retry after the dependency heals pays
 * the activation once more, successfully.
 */
public final class LiveMongoArea {

    private final ScenarioStore scenarioStore;
    private final MongoVisitStore liveStore;

    private final Object activationMonitor = new Object();
    private volatile boolean liveActivated;
    private volatile String activatedDatabaseName;

    public LiveMongoArea(ScenarioStore scenarioStore, MongoVisitStore liveStore) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.liveStore = Objects.requireNonNull(liveStore, "liveStore");
    }

    /**
     * Idempotent activate-once, as a Result: ping + indexes on first call,
     * flip the 'mongo' area LIVE on success. Concurrent readers see either
     * a fully-activated area or a pending activation, never a half state
     * (the store flip happens BEFORE the flag, under the monitor).
     */
    public Result<String> activate() {
        if (liveActivated) {
            return Result.success(activatedDatabaseName);
        }
        synchronized (activationMonitor) {
            if (liveActivated) {
                return Result.success(activatedDatabaseName);
            }
            Result<String> activated = liveStore.activate();
            if (activated instanceof Failed<String> failed) {
                return failed;
            }
            scenarioStore.setDataMode(VisitStore.AREA, ScenarioDataMode.LIVE);
            activatedDatabaseName = activated instanceof Success<String> success
                    ? success.value()
                    : null;
            liveActivated = true;
            return activated;
        }
    }

    /** The throwing form delegates pass through — one shared once-guard, two presentations. */
    public void ensureActivated() {
        if (liveActivated) {
            return;
        }
        Result<String> activated = activate();
        if (activated instanceof Failed<String> failed) {
            throw AppException.of(failed.failure());
        }
    }

    /** Whether activation has already run — the /mode endpoint's honest "current mode" answer. */
    public boolean isLiveActivated() {
        return liveActivated;
    }
}
