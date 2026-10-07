package guide.domain.sqldemo;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The SQL-by-hand lab's WHITELIST (topic 17, demo db:sql-lab): the only four
 * query names a client may ask for, spelled wire-side as cte / window / lag /
 * aggregate. There is no "raw SQL from the client" mode and never will be —
 * a parameter that selects WHICH NAMED QUERY runs is the shape every
 * reporting endpoint should ship (the SQL text lives in the repo, reviewed
 * and versioned like any other code).
 */
public enum LabQueryName {

    CTE("cte", "Recursive CTE: the org chart under a manager",
            "WITH RECURSIVE walks the self-referencing manager_id edge down the tree, one row per report, with depth and a human-readable path."),
    WINDOW("window", "Window functions: rank beside running totals",
            "RANK, ROW_NUMBER and SUM OVER compute per-department placement and running totals WITHOUT collapsing rows — the difference vs GROUP BY, visible side by side."),
    LAG("lag", "LAG: days between consecutive hires",
            "lag(hired_on) OVER (PARTITION BY department ORDER BY hired_on) reaches back one row within each partition — the gap column is plain arithmetic on the result."),
    AGGREGATE("aggregate", "Join + aggregate: one rollup row per department",
            "Aggregate FIRST in the subquery, join AFTER — joining before grouping would multiply rows and corrupt every count. The LEFT JOIN keeps the manager-less executive row honest.");

    private final String wireName;
    private final String title;
    private final String description;

    LabQueryName(String wireName, String title, String description) {
        this.wireName = wireName;
        this.title = title;
        this.description = description;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    /** Parses the wire value; the caller turns anything else into a typed validation failure. */
    public static LabQueryName fromWire(String value) {
        for (LabQueryName name : values()) {
            if (name.wireName.equals(value)) {
                return name;
            }
        }
        throw new IllegalArgumentException(
                "Unknown lab query \"" + value + "\". Expected one of: cte, window, lag, aggregate.");
    }
}
