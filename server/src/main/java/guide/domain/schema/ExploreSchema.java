package guide.domain.schema;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case: explore the schema — every user table, or one named table. The
 * one judgment this layer owns is the miss: an unknown table name is a typed
 * NOT_FOUND failure whose details carry the KNOWN names (so the client can
 * tell the user what to pick), never an exception from the driver and never
 * a silently empty list — "no such table" and "all tables" are different
 * facts, exactly like "no such post" and "empty collection" on the wire.
 */
public class ExploreSchema {

    private final SchemaCatalog catalog;

    public ExploreSchema(SchemaCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** All tables when {@code table} is null/blank; otherwise that one table. */
    public Result<List<TableInsight>> explore(String table) {
        if (table == null || table.isBlank()) {
            List<TableInsight> all = catalog.tableNames().stream()
                    .map(catalog::table)
                    .flatMap(Optional::stream)
                    .toList();
            return Result.success(all);
        }
        String wanted = table.strip();
        return catalog.table(wanted)
                .<Result<List<TableInsight>>>map(insight -> Result.success(List.of(insight)))
                .orElseGet(() -> Result.failed(notFound(wanted)));
    }

    private Failure notFound(String wanted) {
        return Failure.of(FailureKind.NOT_FOUND,
                "no user table named \"" + wanted + "\" exists in schema public",
                java.util.Map.of("table", wanted, "known", catalog.tableNames()));
    }
}
