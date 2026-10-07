package guide.presentation.demo;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Topic 16's demos against REAL PostgreSQL (Testcontainers, postgres:17, via
 * @ServiceConnection — the exact wiring production runs, container for host;
 * grpc moves to an ephemeral port because this context cannot share the
 * suite's cached one).
 *
 * All assertions are STRUCTURAL on purpose: the explorer must surface
 * PostgreSQL's own description of the posts table (columns, PK, both
 * indexes, the V4 annotations), unknown tables answer the typed 404, the
 * uuid experiment must show v7 ordered vs v4 scattered WITHOUT pinning any
 * byte count or duration (those move with PostgreSQL version and page
 * alignment), and the lab tables must be EMPTY again afterwards — the demo
 * cleans up after itself. Lazy activation is exercised end to end: the very
 * first MockMvc call below runs the migrations, and the response honestly
 * reports the db area as live.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class SqlSchemaDemoIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void explorerServesTheRealCatalogForEveryTable() throws Exception {
        mockMvc.perform(get("/api/demo/db/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("db"))
                .andExpect(jsonPath("$.dataMode").value("live"))
                .andExpect(jsonPath("$.tables[*].name", hasItem("posts")))
                .andExpect(jsonPath("$.tables[*].name", hasItem("audit_log")))
                .andExpect(jsonPath("$.tables[*].name", hasItem("uuid_v4_demo")))
                .andExpect(jsonPath("$.tables[*].name", hasItem("uuid_v7_demo")))
                .andExpect(jsonPath("$.tables[*].name", hasItem("flyway_schema_history")));
    }

    @Test
    void thePostsTableArrivesAsPostgresqlDescribesIt() throws Exception {
        mockMvc.perform(get("/api/demo/db/schema").param("table", "posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].name").value("posts"))
                .andExpect(jsonPath("$.tables[0].comment").isNotEmpty())
                .andExpect(jsonPath("$.tables[0].columns[?(@.name == 'id')].type").value(hasItem("uuid")))
                .andExpect(jsonPath("$.tables[0].columns[?(@.name == 'title')].type")
                        .value(hasItem("character varying(120)")))
                .andExpect(jsonPath("$.tables[0].columns[?(@.name == 'tags')].type").value(hasItem("text[]")))
                .andExpect(jsonPath("$.tables[0].columns[?(@.name == 'created_at')].type")
                        .value(hasItem("timestamp with time zone")))
                .andExpect(jsonPath("$.tables[0].columns[?(@.name == 'version')].nullable")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$.tables[0].constraints[?(@.kind == 'PRIMARY KEY')].name")
                        .value(hasItem("posts_pkey")))
                .andExpect(jsonPath("$.tables[0].constraints[?(@.kind == 'CHECK')].name")
                        .value(hasItem("posts_status_check")))
                .andExpect(jsonPath("$.tables[0].indexes[*].name", hasItem("posts_status_idx")))
                .andExpect(jsonPath("$.tables[0].indexes[*].name", hasItem("posts_created_at_desc_idx")))
                .andExpect(jsonPath("$.tables[0].totalBytes", greaterThan(0)));
    }

    @Test
    void unknownTablesAnswerTheTyped404NamingTheKnownOnes() throws Exception {
        mockMvc.perform(get("/api/demo/db/schema").param("table", "dropout"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.known", hasItem("posts")));

        mockMvc.perform(get("/api/demo/db/schema").param("table", "pg_tables"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        mockMvc.perform(get("/api/demo/db/schema").param("table", "posts; DROP TABLE posts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void ddlSummaryIsCatalogDerivedAndSaysSo() throws Exception {
        mockMvc.perform(get("/api/demo/db/schema/ddl/audit_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").value("audit_log"))
                .andExpect(jsonPath("$.ddl").value(org.hamcrest.Matchers.containsString(
                        "catalog-derived summary")))
                .andExpect(jsonPath("$.ddl").value(org.hamcrest.Matchers.containsString(
                        "CREATE TABLE audit_log")))
                .andExpect(jsonPath("$.ddl").value(org.hamcrest.Matchers.containsString(
                        "PRIMARY KEY (id)")))
                .andExpect(jsonPath("$.ddl").value(org.hamcrest.Matchers.containsString(
                        "CREATE INDEX audit_log_entity_idx")));
    }

    @Test
    void uuidExperimentProvesLocalityAndCleansUpAfterItself() throws Exception {
        mockMvc.perform(post("/api/demo/db/uuid/compare").param("rows", "400"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(400))
                .andExpect(jsonPath("$.uuidV7.keyOrderTracksInsertion").value(true))
                .andExpect(jsonPath("$.uuidV7.outOfOrderPairs").value(0))
                .andExpect(jsonPath("$.uuidV4.keyOrderTracksInsertion").value(false))
                .andExpect(jsonPath("$.uuidV4.outOfOrderPairs", greaterThan(0)))
                .andExpect(jsonPath("$.uuidV4.primaryKeyIndexBytes", greaterThan(0)))
                .andExpect(jsonPath("$.uuidV7.primaryKeyIndexBytes", greaterThan(0)))
                .andExpect(jsonPath("$.uuidV4.idsInKeyOrder").isArray())
                .andExpect(jsonPath("$.uuidV7.idsInKeyOrder[0]").isNotEmpty())
                .andExpect(jsonPath("$.verdict").isNotEmpty());

        // The finally-block contract: both lab tables empty again, demo idempotent.
        assertLabTablesEmpty();
        mockMvc.perform(post("/api/demo/db/uuid/compare").param("rows", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(50));
        assertLabTablesEmpty();
    }

    @Test
    void rowBoundsAnswerTheValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/demo/db/uuid/compare").param("rows", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        mockMvc.perform(post("/api/demo/db/uuid/compare").param("rows", "99999999"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    private void assertLabTablesEmpty() {
        assertEquals(0, count("uuid_v4_demo"), "the v4 lab table is truncated after the run");
        assertEquals(0, count("uuid_v7_demo"), "the v7 lab table is truncated after the run");
    }

    private long count(String table) {
        Long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return rows == null ? -1 : rows;
    }
}
