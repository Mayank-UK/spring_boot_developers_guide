package guide.core.scenario;

/**
 * A partial update for {@link ScenarioStore}: null means "leave unchanged".
 * The controller builds one only from a validated JSON body; the store still
 * defends its own invariants against runtime-crafted patches.
 */
public record ScenarioPatch(ScenarioDataMode dataMode, Boolean injectFailure) {

    public static ScenarioPatch empty() {
        return new ScenarioPatch(null, null);
    }
}
