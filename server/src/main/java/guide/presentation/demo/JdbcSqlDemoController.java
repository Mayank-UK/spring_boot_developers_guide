package guide.presentation.demo;

import guide.domain.sqldemo.AuditPostTransfer;
import guide.domain.sqldemo.AuditTrailStore.TrailEntry;
import guide.domain.sqldemo.DbMigrationsExhibit;
import guide.domain.sqldemo.LabQueryName;
import guide.domain.sqldemo.ReadAuditTrail;
import guide.domain.sqldemo.RunInventoryRace;
import guide.domain.sqldemo.RunLabQuery;
import guide.domain.sqldemo.RunSqlSafetyShowdown;
import guide.domain.sqldemo.RunSqlSafetyShowdown.Showdown;
import guide.domain.sqldemo.SearchPosts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Topic 17 (jdbc-sql) demo endpoints — nine thin handlers under
 * /api/demo/db, each doing exactly the house job: parse the input, call one
 * use case, hand back its value. Every request against these endpoints runs
 * real SQL on real PostgreSQL (the db area is LIVE by wiring); the learning
 * lives in domain/sqldemo + data/sqldemo, none of it here.
 *
 * Surface map:
 *   POST /sql/safety          — one payload, two doors: bind parameter vs concatenation
 *   GET  /migrations          — the flyway_schema_history ledger + state sentence
 *   POST /migrations/apply    — idempotent programmatic migrate (SchemaMigrator)
 *   GET  /sql/lab?query=      — whitelisted named queries over the V11 dataset (no query → catalog)
 *   GET  /search?q=&limit=    — websearch FTS ranked + ILIKE counted + three EXPLAIN plans
 *   POST /race/run            — N concurrent reservations, three strategies, one scoreboard
 *   POST /audit/transfer      — post UPDATE + audit INSERT in one transaction (failAfter proves rollback)
 *   GET  /audit/trail?entityId= — the trail for one entity, oldest first
 */
@Tag(name = "SQL demos", description = "Topic 17's live-PostgreSQL demos: injection, migrations, SQL by hand, search, the oversell race, and in-transaction audit.")
@RestController
@RequestMapping("/api/demo/db")
public class JdbcSqlDemoController {

    private final RunSqlSafetyShowdown showdown;
    private final DbMigrationsExhibit migrations;
    private final RunLabQuery lab;
    private final SearchPosts search;
    private final RunInventoryRace race;
    private final AuditPostTransfer transfer;
    private final ReadAuditTrail trail;

    public JdbcSqlDemoController(RunSqlSafetyShowdown showdown, DbMigrationsExhibit migrations,
            RunLabQuery lab, SearchPosts search, RunInventoryRace race,
            AuditPostTransfer transfer, ReadAuditTrail trail) {
        this.showdown = showdown;
        this.migrations = migrations;
        this.lab = lab;
        this.search = search;
        this.race = race;
        this.transfer = transfer;
        this.trail = trail;
    }

    // ---- demo db:sql-safety ----

    record SafetyRequest(String payload) {
    }

    @Operation(summary = "Run one payload through both query doors",
            operationId = "runSqlSafetyShowdown",
            description = "The payload runs as a bind parameter (harmless value) AND through real "
                    + "string concatenation (structure) against a sandbox table; the response "
                    + "contrasts the two outcomes. Empty payload runs the classic ' OR '1'='1.")
    @PostMapping(value = "/sql/safety", produces = MediaType.APPLICATION_JSON_VALUE)
    Showdown sqlSafety(@RequestBody(required = false) SafetyRequest request) {
        return showdown.run(request == null ? null : request.payload());
    }

    // ---- demo db:migrations ----

    @Operation(summary = "The applied migration ledger", operationId = "getMigrationHistory",
            description = "flyway_schema_history read back — versions, descriptions, install "
                    + "times — plus the one-sentence state summary.")
    @GetMapping(value = "/migrations", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.sqldemo.MigrationCatalog.History migrationsHistory() {
        return migrations.history();
    }

    @Operation(summary = "Apply pending migrations (idempotent)", operationId = "applyPendingMigrations",
            description = "One deliberate SchemaMigrator.migrate() call — usually a no-op, "
                    + "which is exactly the point being demonstrated.")
    @PostMapping(value = "/migrations/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.sqldemo.MigrationCatalog.Application applyMigrations() {
        return migrations.applyPending();
    }

    // ---- demo db:sql-lab ----

    record LabCatalog(List<LabQueryName> available) {
    }

    @Operation(summary = "Run one whitelisted lab query", operationId = "runSqlLabQuery",
            description = "query=cte|window|lag|aggregate over the V11 lab dataset — the SQL "
                    + "travels WITH its rows. No query parameter returns the catalog. Raw SQL "
                    + "from clients is not a mode this endpoint has, on purpose.")
    @GetMapping(value = "/sql/lab", produces = MediaType.APPLICATION_JSON_VALUE)
    Object sqlLab(@RequestParam(name = "query", required = false) String query) {
        return query == null ? new LabCatalog(lab.catalog()) : lab.run(query);
    }

    // ---- demo db:search ----

    @Operation(summary = "Full-text search with the plans beside it", operationId = "searchPosts",
            description = "websearch_to_tsquery over the V12 tsvector column, ranked by "
                    + "ts_rank with headline excerpts; the same term counted via ILIKE; and "
                    + "EXPLAIN plans for both (plus the GIN path with seq scans forced off).")
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.sqldemo.PostSearch.SearchReport search(@RequestParam("q") String q,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return search.run(q, limit);
    }

    // ---- demo db:race ----

    record RaceRequest(String strategy, Integer concurrency, Integer initialStock) {
    }

    @Operation(summary = "Run the oversell race under one strategy", operationId = "runInventoryRace",
            description = "strategy=naive|guarded|atomic, N genuinely-concurrent reservation "
                    + "attempts against one inventory row under READ COMMITTED — the scoreboard "
                    + "shows which strategies oversold.")
    @PostMapping(value = "/race/run", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.sqldemo.RaceOutcome race(@RequestBody RaceRequest request) {
        return race.run(request.strategy(), request.concurrency(), request.initialStock());
    }

    // ---- demo db:audit ----

    record TransferRequest(String postId, String title, String changedBy, Boolean failAfter) {
    }

    record TrailView(String entityId, List<TrailEntry> entries) {
    }

    @Operation(summary = "Audited title transfer in one transaction", operationId = "transferWithAudit",
            description = "Updates a post and writes its audit_log row inside ONE transaction; "
                    + "failAfter=true crashes between the two writes and the proof shows the "
                    + "rollback left neither behind.")
    @PostMapping(value = "/audit/transfer", produces = MediaType.APPLICATION_JSON_VALUE)
    AuditPostTransfer.TransferProof auditTransfer(@RequestBody TransferRequest request) {
        return transfer.transfer(request.postId(), request.title(), request.changedBy(),
                Boolean.TRUE.equals(request.failAfter()));
    }

    @Operation(summary = "The audit trail for one entity", operationId = "readAuditTrail",
            description = "Every trail row for entityId, oldest first — the ledger the transfer "
                    + "demo writes, read back the way an investigator would.")
    @GetMapping(value = "/audit/trail", produces = MediaType.APPLICATION_JSON_VALUE)
    TrailView auditTrail(@RequestParam("entityId") String entityId) {
        return new TrailView(entityId, trail.read(entityId));
    }
}
