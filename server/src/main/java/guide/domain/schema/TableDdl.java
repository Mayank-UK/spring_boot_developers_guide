package guide.domain.schema;

/**
 * One table's DDL-ish summary: the table name plus the catalog-derived
 * rendering (see SummarizeTableDdl for exactly what that means and does not
 * claim to mean).
 */
public record TableDdl(String table, String ddl) {
}
