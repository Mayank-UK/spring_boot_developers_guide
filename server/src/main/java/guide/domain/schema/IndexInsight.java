package guide.domain.schema;

/**
 * One index: its name, the columns it keys on (comma-joined in key order;
 * expression keys appear in the definition, not this list — the catalog has
 * no column name for an expression), the full CREATE INDEX definition
 * (pg_get_indexdef, verbatim), and the index's on-disk size in bytes plus
 * the human-readable pretty form — the cost side of "every index is a
 * data structure you maintain on every write".
 */
public record IndexInsight(String name, String columns, String definition, long bytes, String size) {
}
