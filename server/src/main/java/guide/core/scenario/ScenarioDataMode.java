package guide.core.scenario;

import com.fasterxml.jackson.annotation.JsonValue;

/** The two data modes every area can run in: in-memory fakes or live infrastructure. */
public enum ScenarioDataMode {
    FAKE("fake"),
    LIVE("live");

    private final String wireName;

    ScenarioDataMode(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Parses the wire value ("fake"/"live"); anything else throws IllegalArgumentException. */
    public static ScenarioDataMode fromWire(String value) {
        for (ScenarioDataMode mode : values()) {
            if (mode.wireName.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Invalid dataMode \"" + value + "\". Expected one of: fake, live.");
    }
}
