package guide.domain.sqldemo;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The oversell race's three strategies (topic 17, demo db:race) — one enum
 * because they are one decision, spelled three ways, and the wire names the
 * client sends are the words the prose teaches with:
 *
 * - naive   — SELECT the stock, decide in Java, then write the computed
 *             value back. The check and the act are two statements with a
 *             gap between them; every other thread fits through that gap.
 * - guarded — SELECT ... FOR UPDATE inside a transaction: the row lock
 *             makes read-decide-write ONE serialized step per writer.
 * - atomic  — ONE statement, UPDATE ... WHERE stock >= 1: the guard and the
 *             decrement are indivisible, and rows-affected is the verdict.
 */
public enum RaceStrategy {

    NAIVE("naive"),
    GUARDED("guarded"),
    ATOMIC("atomic");

    private final String wireName;

    RaceStrategy(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Parses the wire value (naive|guarded|atomic); the caller maps unknowns to a typed failure. */
    public static RaceStrategy fromWire(String value) {
        for (RaceStrategy strategy : values()) {
            if (strategy.wireName.equals(value)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException(
                "Unknown strategy \"" + value + "\". Expected one of: naive, guarded, atomic.");
    }
}
