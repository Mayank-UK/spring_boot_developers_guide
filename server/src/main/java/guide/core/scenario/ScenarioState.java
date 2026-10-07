package guide.core.scenario;

/**
 * The runtime scenario: global data mode plus failure injection. Immutable
 * value; the mutable home is {@link ScenarioStore}.
 */
public record ScenarioState(ScenarioDataMode dataMode, boolean injectFailure) {

    public ScenarioState {
        java.util.Objects.requireNonNull(dataMode, "dataMode");
    }

    public static ScenarioState defaultValue() {
        return new ScenarioState(ScenarioDataMode.FAKE, false);
    }

    /**
     * Reads the SCENARIO_* environment values (null/empty treated as unset) and
     * validates them exactly like the app config validates its env — a bad value
     * throws at startup so the process never boots misconfigured.
     */
    public static ScenarioState fromEnvironment(String rawDataMode, String rawInjectFailure) {
        return new ScenarioState(parseDataMode(rawDataMode), parseInjectFailure(rawInjectFailure));
    }

    private static ScenarioDataMode parseDataMode(String raw) {
        String value = raw == null || raw.isEmpty() ? ScenarioDataMode.FAKE.wireName() : raw;
        try {
            return ScenarioDataMode.fromWire(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid SCENARIO_DATA_MODE \"" + value + "\". Expected one of: fake, live.", e);
        }
    }

    private static boolean parseInjectFailure(String raw) {
        String value = raw == null || raw.isEmpty() ? "false" : raw;
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalStateException(
                    "Invalid SCENARIO_INJECT_FAILURE \"" + value + "\". Expected \"true\" or \"false\".");
        }
        return value.equals("true");
    }
}
