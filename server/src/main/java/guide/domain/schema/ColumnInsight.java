package guide.domain.schema;

/**
 * One column as the catalog describes it: the NAME, the TYPE exactly as
 * PostgreSQL formats it (format_type — so varchar(120), text[], timestamptz
 * and numeric(8,2) arrive verbatim, which is the teaching artifact), the
 * NULLABILITY, the DEFAULT expression as written (null when none — never the
 * string "null"), and the COMMENT left by V4's COMMENT ON migration (null
 * when absent).
 */
public record ColumnInsight(String name, String type, boolean nullable,
        String defaultValue, String comment) {
}
