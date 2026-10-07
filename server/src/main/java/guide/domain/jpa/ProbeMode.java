package guide.domain.jpa;

/**
 * The four fetch shapes the probe measures over the same page of posts (see
 * the n-plus-one section): plain lazy loading (the N+1 itself), JOIN FETCH,
 * an {@code @EntityGraph}, and Hibernate's {@code @BatchSize} batching.
 * Wire spellings are the query parameter's words.
 */
public enum ProbeMode {

    N_PLUS_ONE("n-plus-1"),
    JOIN_FETCH("join-fetch"),
    ENTITY_GRAPH("entity-graph"),
    BATCH("batch");

    private final String wireName;

    ProbeMode(String wireName) {
        this.wireName = wireName;
    }

    /** The ?mode= spelling the demo API accepts. */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire spelling; unknown values throw (the edge turns that into a validation failure). */
    public static ProbeMode fromWire(String wire) {
        for (ProbeMode mode : values()) {
            if (mode.wireName.equals(wire)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "unknown probe mode \"" + wire + "\" — expected one of n-plus-1, join-fetch, entity-graph, batch");
    }
}
