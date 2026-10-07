package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.ErrorResponse;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.errors.UnavailableException;
import guide.core.logging.RequestIdFilter;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.schema.CompareKeyStrategies;
import guide.domain.schema.ExploreSchema;
import guide.domain.schema.KeyStrategyReport;
import guide.domain.schema.SchemaCatalog;
import guide.domain.schema.SummarizeTableDdl;
import guide.domain.schema.TableDdl;
import guide.domain.schema.TableInsight;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 16 (sql-schema) — the database describing itself:
 *
 * - GET  /api/demo/db/schema?table=posts   → columns, constraints, indexes,
 *   sizes for one table (omit ?table for every user table) — read live from
 *   information_schema + pg_catalog, the same catalogs psql's \d reads.
 * - GET  /api/demo/db/schema/ddl/{table}   → the table's catalog-derived
 *   DDL-ish summary (honestly labeled: PostgreSQL does not store the
 *   original CREATE script).
 * - POST /api/demo/db/uuid/compare?rows=N  → the uuid v4 vs v7 experiment:
 *   N rows into each V3 lab table, index bytes + insertion-order locality
 *   measured, tables truncated afterwards.
 *
 * The 'db' scenario area activates lazily inside the FIRST call (migrations
 * run once, the area flips LIVE) — so these endpoints honestly answer 503
 * (typed unavailable envelope) while PostgreSQL is down and succeed on the
 * very next call after it heals, with no restart in between. injectFailure
 * guards every endpoint first, the same ScenarioStore exhibit as the posts
 * controller.
 */
@Tag(name = "Schema", description = "The sql-schema topic's demos: the live catalog explorer "
        + "and the uuid v4-vs-v7 key-strategy experiment.")
@RestController
@RequestMapping("/api/demo/db")
public class SqlSchemaDemoController {

    /** The response carries the area's mode so the demo can SHOW activation happening. */
    record SchemaExplorerView(String area, String dataMode, List<TableInsight> tables) {
    }

    private final ExploreSchema exploreSchema;
    private final SummarizeTableDdl summarizeTableDdl;
    private final CompareKeyStrategies compareKeyStrategies;
    private final ScenarioStore scenarioStore;

    public SqlSchemaDemoController(ExploreSchema exploreSchema, SummarizeTableDdl summarizeTableDdl,
            CompareKeyStrategies compareKeyStrategies, ScenarioStore scenarioStore) {
        this.exploreSchema = exploreSchema;
        this.summarizeTableDdl = summarizeTableDdl;
        this.compareKeyStrategies = compareKeyStrategies;
        this.scenarioStore = scenarioStore;
    }

    /**
     * The explorer: every user table, or one named table. An unknown name is
     * the typed 404 (the use case's failure carries the known names in its
     * details) — never a driver error, never a silently empty list.
     */
    @Operation(summary = "Explore the schema — columns, constraints, indexes, sizes",
            operationId = "exploreSchema",
            description = "Reads information_schema + pg_catalog live (the same catalogs "
                    + "psql's \\d reads). Omit ?table for every user table; an unknown table "
                    + "answers the typed 404 envelope naming the known tables.")
    @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SchemaExplorerView> schema(@RequestParam(required = false) String table) {
        requireAvailable();
        return switch (exploreSchema.explore(table)) {
            case Success<List<TableInsight>> success -> ResponseEntity.ok(new SchemaExplorerView(
                    SchemaCatalog.AREA, dataMode(), success.value()));
            case Failed<List<TableInsight>> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * The DDL summary: catalog-derived, labeled as a reconstruction — the
     * constraint and index definitions are pg_get_constraintdef /
     * pg_get_indexdef's own words.
     */
    @Operation(summary = "One table's catalog-derived DDL summary", operationId = "tableDdl",
            description = "Reconstructed from the catalogs (PostgreSQL does not store the "
                    + "original CREATE script); the summary says so in its first line.")
    @GetMapping(value = "/schema/ddl/{table}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TableDdl> ddl(@PathVariable String table) {
        requireAvailable();
        return switch (summarizeTableDdl.summarize(table)) {
            case Success<TableDdl> success -> ResponseEntity.ok(success.value());
            case Failed<TableDdl> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * The key-strategy experiment. rows defaults to 5000 (enough for the
     * index-size gap to appear); out-of-range values answer the validation
     * envelope, and the run truncates the lab tables no matter how it ends.
     */
    @Operation(summary = "uuid v4 vs v7 — insert N rows each, measure the indexes",
            operationId = "compareKeyStrategies",
            description = "Reports primary-key index bytes and key-order-vs-insertion-order "
                    + "locality per strategy, then truncates both lab tables (idempotent).")
    @PostMapping(value = "/uuid/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> compare(@RequestParam(defaultValue = "5000") int rows,
            HttpServletRequest request) {
        requireAvailable();
        if (rows < 1 || rows > CompareKeyStrategies.MAX_ROWS) {
            return unprocessable(Failure.of(FailureKind.VALIDATION,
                    "rows must be between 1 and " + CompareKeyStrategies.MAX_ROWS,
                    Map.of("fields", Map.of("rows",
                            "must be between 1 and " + CompareKeyStrategies.MAX_ROWS))), request);
        }
        return switch (compareKeyStrategies.compare(rows)) {
            case Success<KeyStrategyReport> success -> ResponseEntity.ok(success.value());
            case Failed<KeyStrategyReport> failed -> throw AppException.of(failed.failure());
        };
    }

    // ---- edge helpers ----

    /** The area's mode as the wire spells it — "live" once activation has run. */
    private String dataMode() {
        return scenarioStore.dataModeFor(SchemaCatalog.AREA) == ScenarioDataMode.LIVE
                ? "live" : "fake";
    }

    /** The 422 mapping for semantic validation at this edge, same envelope as every failure. */
    private static ResponseEntity<ErrorResponse> unprocessable(Failure failure,
            HttpServletRequest request) {
        return ResponseEntity.status(422)
                .body(ErrorResponse.of(failure.kind().errorCode(), failure.message(),
                        requestId(request), failure.details()));
    }

    /** ScenarioStore exhibit: with injectFailure on, the demo dependency is "down" — 503. */
    private void requireAvailable() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "schema demos are unavailable by scenario (injectFailure=true)",
                    Map.of("area", SchemaCatalog.AREA));
        }
    }

    /** The request id the filter stamped — same fallback chain as the advice's. */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isEmpty()) {
            return value;
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }
}
