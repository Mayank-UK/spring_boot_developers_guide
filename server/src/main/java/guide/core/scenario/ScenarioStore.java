package guide.core.scenario;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-switchable demo scenario: fake/live data mode plus failure
 * injection, with per-area mode overrides for the data adapters later phases
 * add (an area left unoverridden follows the global mode).
 *
 * A plain mutable bean by design — demo endpoints flip it at runtime through
 * PATCH /api/demo/scenario. Thread safety: the global state is volatile and
 * patches apply under a lock; area overrides live in a ConcurrentHashMap.
 */
public final class ScenarioStore {

    private final ScenarioState initial;
    private final Map<String, ScenarioDataMode> areaModes = new ConcurrentHashMap<>();
    private volatile ScenarioState state;

    public ScenarioStore() {
        this(ScenarioState.defaultValue());
    }

    public ScenarioStore(ScenarioState initial) {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.state = initial;
    }

    /** Current global state — what GET /api/demo/scenario serves. */
    public ScenarioState get() {
        return state;
    }

    /**
     * Applies a partial update (null fields keep their current value) and
     * returns the new state. Values are typed here, so the only invariant left
     * to defend is non-nullness — the controller validates the raw body first.
     */
    public synchronized ScenarioState apply(ScenarioPatch patch) {
        Objects.requireNonNull(patch, "patch");
        ScenarioState current = state;
        ScenarioState next = new ScenarioState(
                patch.dataMode() != null ? patch.dataMode() : current.dataMode(),
                patch.injectFailure() != null ? patch.injectFailure() : current.injectFailure());
        state = next;
        return next;
    }

    /** The mode one data area runs in: its override when present, else the global mode. */
    public ScenarioDataMode dataModeFor(String area) {
        Objects.requireNonNull(area, "area");
        ScenarioDataMode override = areaModes.get(area);
        return override != null ? override : state.dataMode();
    }

    /** Overrides one area's data mode without touching the global state. */
    public void setDataMode(String area, ScenarioDataMode mode) {
        areaModes.put(Objects.requireNonNull(area, "area"), Objects.requireNonNull(mode, "mode"));
    }

    /** Back to the initial state the store was created with; area overrides clear too. */
    public synchronized void reset() {
        state = initial;
        areaModes.clear();
    }
}
