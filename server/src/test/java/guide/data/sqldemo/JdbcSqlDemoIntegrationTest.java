package guide.data.sqldemo;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Topic 17's six demos against the REAL application context and REAL
 * PostgreSQL (@ServiceConnection, postgres:17 — one dialect, no H2 ever).
 * Every demo endpoint is exercised through the same HTTP surface the client
 * consoles use, and the claims the demos make are asserted, not trusted:
 *
 * - sql-safety: the classic ' OR '1'='1 leaks the sandbox's secret rows ONLY
 *   through the concatenation path; the bind-parameter path returns zero
 *   rows; and a UNION escape aimed at posts DIES at the restricted role's
 *   permission boundary (the blast-radius cap, proven not promised).
 * - migrations: the ledger lists V1 plus V10–V12 as applied, and a second
 *   deliberate apply executes zero scripts (Flyway's idempotence).
 * - sql-lab: exact, deterministic results over the V11 dataset — Liskov's
 *   platform salary rank 2, platform payroll 53,900,000 cents, Knuth hired
 *   140 days after Goldberg, the manager-less executive row kept by LEFT
 *   JOIN — and unknown query names are validation failures, never SQL.
 * - search: FTS ranks hits the ILIKE count misses (stemming: errors ≈
 *   error), and all three plan readouts come back from the real planner.
 * - race: naive OVERSELLS deterministically (the barrier makes the
 *   check-then-act gap deterministic, not lucky); guarded and atomic never
 *   do — exactly initialStock claims succeed under either.
 * - audit: the transfer commits post change + trail row together; failAfter
 *   rolls BOTH back, and the after-state is re-read from committed truth.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class JdbcSqlDemoIntegrationTest {

    /** A posts-seed id the audit demo can mutate (its title is restored per test). */
    private static final String NEWEST_SEED = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    private static final String NEWEST_SEED_TITLE = "The uniform interface is the contract that scales";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DbDemoActivation activation;

    /** The audit demos mutate shared state; every test starts from a clean trail + title. */
    @BeforeEach
    void resetAuditSurfaces() {
        // Pay the (idempotent) migration + seed bill up front, so the resets
        // below run against tables that exist — and so the FIRST test's
        // assertions read the same world as the last one's.
        activation.ensureReady();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("UPDATE posts SET title = ? WHERE id = ?", NEWEST_SEED_TITLE,
                java.util.UUID.fromString(NEWEST_SEED));
    }

    // ---- demo db:sql-safety ----

    @Test
    void classicPayloadIsBlockedByBindsAndLeaksThroughConcatenation() throws Exception {
        mockMvc.perform(post("/api/demo/db/sql/safety").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                // the bind-parameter door: the payload is a VALUE — nothing matches it
                .andExpect(jsonPath("$.payload").value("' OR '1'='1"))
                .andExpect(jsonPath("$.parameterized.rows.length()").value(0))
                .andExpect(jsonPath("$.parameterized.sqlShown").value(
                        "SELECT username, role, secret FROM demo_injection WHERE username = ?"))
                // the concatenation door: the payload became STRUCTURE — every row leaks
                .andExpect(jsonPath("$.vulnerable.rows.length()").value(3))
                .andExpect(jsonPath("$.vulnerable.rows[0].secret").value("flag{ada-reads-everything}"))
                .andExpect(jsonPath("$.vulnerable.sqlShown").value(
                        "SELECT username, role, secret FROM demo_injection WHERE username = '' OR '1'='1'"))
                .andExpect(jsonPath("$.verdict").value(org.hamcrest.Matchers.containsString("structure")));
    }

    @Test
    void unionEscapeDiesAtTheRoleBoundary() throws Exception {
        String escape = "' UNION SELECT id::text, title, body FROM posts --";
        mockMvc.perform(post("/api/demo/db/sql/safety").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"" + escape + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterized.rows.length()").value(0))
                .andExpect(jsonPath("$.vulnerable.rows.length()").value(0))
                .andExpect(jsonPath("$.vulnerable.containmentError").value(
                        org.hamcrest.Matchers.containsString("permission denied")))
                .andExpect(jsonPath("$.verdict").value(org.hamcrest.Matchers.containsString("least privilege")));
    }

    // ---- demo db:migrations ----

    @Test
    void migrationsLedgerListsEverythingAndSecondApplyIsANoOp() throws Exception {
        mockMvc.perform(get("/api/demo/db/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value(org.hamcrest.Matchers.containsString("programmatic")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("0 pending")))
                .andExpect(jsonPath("$.applied.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.applied[0].version").value("1"))
                .andExpect(jsonPath("$.applied[0].description").value("posts"))
                .andExpect(jsonPath("$.applied[?(@.version == '10')].description").value(hasItem("inventory")))
                .andExpect(jsonPath("$.applied[?(@.version == '11')].description").value(hasItem("sql lab")))
                .andExpect(jsonPath("$.applied[?(@.version == '12')].description").value(hasItem("search")));

        mockMvc.perform(post("/api/demo/db/migrations/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationsApplied").value(0))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("idempotent")));
    }

    // ---- demo db:sql-lab ----

    @Test
    void labCatalogListsFourNamedQueries() throws Exception {
        mockMvc.perform(get("/api/demo/db/sql/lab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available.length()").value(4))
                .andExpect(jsonPath("$.available[0]").value("cte"));
    }

    @Test
    void recursiveCteWalksTheOrgChartUnderTheLead() throws Exception {
        mockMvc.perform(get("/api/demo/db/sql/lab").param("query", "cte"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cte"))
                .andExpect(jsonPath("$.sql").value(org.hamcrest.Matchers.containsString("WITH RECURSIVE")))
                // Grace Hopper plus her three reports — not the whole company
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[?(@.name == 'Barbara Liskov')].depth").value(hasItem(1)))
                .andExpect(jsonPath("$.rows[?(@.name == 'Grace Hopper')].path")
                        .value(hasItem("Grace Hopper")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Margaret Hamilton')].path")
                        .value(hasItem("Grace Hopper > Margaret Hamilton")));
    }

    @Test
    void windowFunctionsRankAndRunTotalsWithoutCollapsingRows() throws Exception {
        mockMvc.perform(get("/api/demo/db/sql/lab").param("query", "window"))
                .andExpect(status().isOk())
                // all twelve staff, one row each — GROUP BY would have collapsed them
                .andExpect(jsonPath("$.rows.length()").value(12))
                .andExpect(jsonPath("$.rows[?(@.name == 'Barbara Liskov')].salary_rank").value(hasItem(2)))
                .andExpect(jsonPath("$.rows[?(@.name == 'Margaret Hamilton')].running_total_cents")
                        .value(hasItem(53900000)))
                .andExpect(jsonPath("$.rows[?(@.name == 'Grace Hopper')].hire_seq").value(hasItem(1)));
    }

    @Test
    void lagMeasuresTheGapBetweenConsecutiveHiresPerDepartment() throws Exception {
        mockMvc.perform(get("/api/demo/db/sql/lab").param("query", "lag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(12))
                .andExpect(jsonPath("$.rows[?(@.name == 'Donald Knuth')].days_since_previous_hire")
                        .value(hasItem(140)))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ada Lovelace')].previous_hire_on")
                        .value(hasItem(nullValue())));
    }

    @Test
    void aggregateRollupCountsHeadsAndKeepsTheManagerlessRowHonest() throws Exception {
        mockMvc.perform(get("/api/demo/db/sql/lab").param("query", "aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[?(@.department == 'platform')].headcount").value(hasItem(4)))
                .andExpect(jsonPath("$.rows[?(@.department == 'platform')].total_salary_cents")
                        .value(hasItem(53900000)))
                .andExpect(jsonPath("$.rows[?(@.department == 'platform')].manager_name")
                        .value(hasItem("Grace Hopper")))
                // the CEO reports to nobody — LEFT JOIN keeps the row with a null manager
                .andExpect(jsonPath("$.rows[?(@.department == 'executive')].manager_name")
                        .value(hasItem(nullValue())));
    }

    @Test
    void unknownLabQueryIsAValidationFailureNeverSql() throws Exception {
        mockMvc.perform(get("/api/demo/db/sql/lab").param("query", "DROP TABLE lab_staff"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- demo db:search ----

    @Test
    void searchRanksStemmedHitsTheSubstringCountCannotSee() throws Exception {
        mockMvc.perform(get("/api/demo/db/search").param("q", "errors"))
                .andExpect(status().isOk())
                // FTS stems errors → error: the title-weighted "One error envelope…" post
                // ranks FIRST, and the uniform-interface post (body: "one error shape")
                // matches on its body weight — two stemmed hits…
                .andExpect(jsonPath("$.hits.length()").value(2))
                .andExpect(jsonPath("$.hits[0].title").value("One error envelope beats five correct ones"))
                .andExpect(jsonPath("$.hits[1].title").value("The uniform interface is the contract that scales"))
                .andExpect(jsonPath("$.hits[0].rank").value(org.hamcrest.Matchers.greaterThan(0.0)))
                // the mark lands on the BODY hit ("one <b>error</b> shape") — the title hit's
                // excerpt comes from a body that never says the word, and honestly says so
                .andExpect(jsonPath("$.hits[1].excerpt").value(org.hamcrest.Matchers.containsString("<b>")))
                // …while ILIKE '%errors%' finds nothing, because every text says 'error'
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.ftsPlan[0]").value(org.hamcrest.Matchers.containsString("Scan")))
                .andExpect(jsonPath("$.ftsPlanIndexForced[0]")
                        .value(org.hamcrest.Matchers.containsString("Bitmap")))
                // count(*) wraps the LIKE plan in an Aggregate node — the Seq Scan is
                // whatever line carries it, not necessarily the first
                .andExpect(jsonPath("$.likePlan")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Seq Scan"))));
    }

    @Test
    void blankSearchTermIsAValidationFailure() throws Exception {
        mockMvc.perform(get("/api/demo/db/search").param("q", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- demo db:race ----

    @Test
    void naiveCheckThenActOversellsDeterministically() throws Exception {
        mockMvc.perform(post("/api/demo/db/race/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"naive\",\"concurrency\":4,\"initialStock\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(4))
                .andExpect(jsonPath("$.succeeded").value(4))
                .andExpect(jsonPath("$.oversold").value(true))
                .andExpect(jsonPath("$.finalStock").value(0))
                .andExpect(jsonPath("$.finalReserved").value(4))
                .andExpect(jsonPath("$.story").value(org.hamcrest.Matchers.containsString("shelf lies")));
    }

    @Test
    void guardedSelectForUpdateNeverOversells() throws Exception {
        mockMvc.perform(post("/api/demo/db/race/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"guarded\",\"concurrency\":4,\"initialStock\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.declined").value(3))
                .andExpect(jsonPath("$.oversold").value(false))
                .andExpect(jsonPath("$.finalStock").value(0))
                .andExpect(jsonPath("$.finalReserved").value(1));
    }

    @Test
    void atomicGuardedUpdateNeverOversells() throws Exception {
        mockMvc.perform(post("/api/demo/db/race/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"atomic\",\"concurrency\":4,\"initialStock\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.declined").value(3))
                .andExpect(jsonPath("$.oversold").value(false))
                .andExpect(jsonPath("$.finalStock").value(0))
                .andExpect(jsonPath("$.finalReserved").value(1));
    }

    @Test
    void raceValidationRejectsUnknownStrategiesAndPoolBreakingConcurrency() throws Exception {
        mockMvc.perform(post("/api/demo/db/race/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"yolo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/db/race/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"atomic\",\"concurrency\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- demo db:audit ----

    @Test
    void auditedTransferCommitsTheChangeAndItsTrailRowTogether() throws Exception {
        mockMvc.perform(post("/api/demo/db/audit/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + NEWEST_SEED + "\",\"title\":\"Renamed by the audit demo\","
                                + "\"changedBy\":\"integration-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.titleBefore").value(NEWEST_SEED_TITLE))
                .andExpect(jsonPath("$.titleAfter").value("Renamed by the audit demo"))
                .andExpect(jsonPath("$.trailRowsBefore").value(0))
                .andExpect(jsonPath("$.trailRowsAfter").value(1));

        mockMvc.perform(get("/api/demo/db/audit/trail").param("entityId", NEWEST_SEED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].actor").value("integration-test"))
                .andExpect(jsonPath("$.entries[0].payload")
                        .value(org.hamcrest.Matchers.containsString("title-change")));

        // and the change itself is real, read straight off the table
        org.junit.jupiter.api.Assertions.assertEquals("Renamed by the audit demo",
                jdbc.queryForObject("SELECT title FROM posts WHERE id = ?", String.class,
                        java.util.UUID.fromString(NEWEST_SEED)),
                "the committed transfer's title survived into the table");
    }

    @Test
    void failAfterProvesRollbackLeavesNeitherWriteBehind() throws Exception {
        mockMvc.perform(post("/api/demo/db/audit/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + NEWEST_SEED + "\",\"title\":\"Should never stick\","
                                + "\"failAfter\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.titleBefore").value(NEWEST_SEED_TITLE))
                .andExpect(jsonPath("$.titleAfter").value(NEWEST_SEED_TITLE))
                .andExpect(jsonPath("$.trailRowsBefore").value(0))
                .andExpect(jsonPath("$.trailRowsAfter").value(0))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("rolled back")));

        mockMvc.perform(get("/api/demo/db/audit/trail").param("entityId", NEWEST_SEED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));

        org.junit.jupiter.api.Assertions.assertEquals(NEWEST_SEED_TITLE,
                jdbc.queryForObject("SELECT title FROM posts WHERE id = ?", String.class,
                        java.util.UUID.fromString(NEWEST_SEED)),
                "the rolled-back transfer left the original title untouched");
    }

    @Test
    void transferValidationRejectsHostileIdsAndEmptyTitles() throws Exception {
        mockMvc.perform(post("/api/demo/db/audit/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"' OR '1'='1\",\"title\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/db/audit/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + NEWEST_SEED + "\",\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEntityIdIsAnEmptyTrailNotAnError() throws Exception {
        mockMvc.perform(get("/api/demo/db/audit/trail")
                        .param("entityId", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }
}
