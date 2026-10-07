package guide.domain.schema;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Use case: one table's DDL-ish summary, composed honestly from the same
 * catalog insight the explorer serves. This is NOT pg_dump output — the
 * catalog cannot hand back the original CREATE script (PostgreSQL does not
 * store it) — so the summary says so in its own first line and then
 * reconstructs what the catalog DOES know: columns with types, defaults and
 * nullability, pg_get_constraintdef's constraint text, pg_get_indexdef's
 * index text, and the COMMENT ON annotations. Faithful to the source, and
 * explicit about being a reconstruction — the alternative (pretending it is
 * the original DDL) would be teaching a small lie.
 */
public class SummarizeTableDdl {

    private final SchemaCatalog catalog;

    public SummarizeTableDdl(SchemaCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** The table's catalog-derived summary, or the typed not-found failure. */
    public Result<TableDdl> summarize(String table) {
        Objects.requireNonNull(table, "table");
        return catalog.table(table)
                .<Result<TableDdl>>map(insight -> Result.success(new TableDdl(table, render(insight))))
                .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                        "no user table named \"" + table + "\" exists in schema public",
                        java.util.Map.of("table", table, "known", catalog.tableNames()))));
    }

    /** The honest reconstruction — every line traceable to a catalog field. */
    private static String render(TableInsight insight) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- catalog-derived summary (reconstructed from information_schema + pg_catalog;\n")
           .append("-- PostgreSQL does not store the original CREATE script)\n");
        ddl.append("CREATE TABLE ").append(insight.name()).append(" (\n");
        List<String> lines = new ArrayList<>();
        for (ColumnInsight column : insight.columns()) {
            StringBuilder line = new StringBuilder("    ").append(column.name())
                    .append(' ').append(column.type());
            if (column.defaultValue() != null) {
                line.append(" DEFAULT ").append(column.defaultValue());
            }
            if (!column.nullable()) {
                line.append(" NOT NULL");
            }
            lines.add(line.toString());
        }
        for (ConstraintInsight constraint : insight.constraints()) {
            lines.add("    CONSTRAINT " + constraint.name() + " " + constraint.definition());
        }
        ddl.append(String.join(",\n", lines)).append("\n);\n");
        for (IndexInsight index : insight.indexes()) {
            if (!index.name().endsWith("_pkey")) {
                ddl.append(index.definition()).append(";\n");
            }
        }
        if (insight.comment() != null) {
            ddl.append("COMMENT ON TABLE ").append(insight.name())
               .append(" IS '").append(insight.comment().replace("'", "''")).append("';\n");
        }
        return ddl.toString();
    }
}
