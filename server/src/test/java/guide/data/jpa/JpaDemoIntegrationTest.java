package guide.data.jpa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Topic 18's three demos against the REAL application context and REAL
 * PostgreSQL (@ServiceConnection, postgres:17). The claims under test are
 * the probe's NUMBERS — measured, not trusted:
 *
 * - fake-mode default: before any flip, /api/demo/jpa/probe answers with the
 *   typed UNAVAILABLE envelope that names the 'jpa' area and the activation
 *   command (the area seam is part of the teaching);
 * - n-plus-1: for size posts the read phase issues MORE than size queries
 *   (the inequality is the stable assertion — the exact N+1 arithmetic is
 *   the probe's report, not the test's brittleness);
 * - join-fetch and entity-graph: EXACTLY one query each;
 * - batch: strictly between one and the N+1 count;
 * - locking: the stale second writer is rejected (two genuinely independent
 *   transactions, a committed row, a real version column) and surfaces as
 *   the guide's 409 conflict envelope when asked to;
 * - pagination: Page metadata over the 12 seed posts (2 drafts), the count
 *   query included in the cost;
 * - zero residue: every probe rolls its specimens back — afterwards the
 *   posts table is exactly the twelve seeds again.
 *
 * Ordered on purpose: the fake-mode assertion must run BEFORE the live flip.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JpaDemoIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- the area seam: FAKE by default ----

    @Test
    @Order(1)
    void fakeModeAnswersWithTheTypedUnavailableEnvelope() throws Exception {
        mockMvc.perform(get("/api/demo/jpa/probe?mode=n-plus-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("PATCH /api/demo/scenario")));
    }

    // ---- the probe: measured numbers per fetch shape ----

    @Test
    @Order(2)
    void nPlusOneIssuesMoreQueriesThanPosts() throws Exception {
        activateLive();
        mockMvc.perform(get("/api/demo/jpa/probe?mode=n-plus-1&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("n-plus-1"))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.postsReturned").value(5))
                .andExpect(jsonPath("$.entriesLoaded").value(15))
                .andExpect(jsonPath("$.queriesIssued").value(
                        org.hamcrest.Matchers.greaterThan(5)))
                .andExpect(jsonPath("$.verdict").value(
                        org.hamcrest.Matchers.containsString("N+1 confirmed")));
    }

    @Test
    @Order(3)
    void joinFetchAndEntityGraphEachIssueExactlyOneQuery() throws Exception {
        mockMvc.perform(get("/api/demo/jpa/probe?mode=join-fetch&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queriesIssued").value(1))
                .andExpect(jsonPath("$.postsReturned").value(5))
                .andExpect(jsonPath("$.entriesLoaded").value(15))
                .andExpect(jsonPath("$.verdict").value(
                        org.hamcrest.Matchers.containsString("ONE query")));
        mockMvc.perform(get("/api/demo/jpa/probe?mode=entity-graph&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queriesIssued").value(1))
                .andExpect(jsonPath("$.entriesLoaded").value(15))
                .andExpect(jsonPath("$.verdict").value(
                        org.hamcrest.Matchers.containsString("EntityGraph")));
    }

    @Test
    @Order(4)
    void batchCollapsesTheLazyStormBelowTheNPlusOneCount() throws Exception {
        org.springframework.test.web.servlet.MvcResult nPlusOne = mockMvc
                .perform(get("/api/demo/jpa/probe?mode=n-plus-1&size=5"))
                .andReturn();
        int nPlusOneQueries = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(nPlusOne.getResponse().getContentAsString())
                .get("queriesIssued").asInt();
        mockMvc.perform(get("/api/demo/jpa/probe?mode=batch&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queriesIssued").value(
                        org.hamcrest.Matchers.lessThan(nPlusOneQueries)))
                .andExpect(jsonPath("$.queriesIssued").value(
                        org.hamcrest.Matchers.greaterThan(1)))
                .andExpect(jsonPath("$.entriesLoaded").value(15))
                .andExpect(jsonPath("$.verdict").value(
                        org.hamcrest.Matchers.containsString("BatchSize")));
    }

    // ---- locking: a real race, a real rejection, a real 409 ----

    @Test
    @Order(5)
    void optimisticConflictRejectsTheStaleWriterAndMapsToTheConflictEnvelope() throws Exception {
        mockMvc.perform(get("/api/demo/jpa/probe/locking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staleWriterRejected").value(true))
                .andExpect(jsonPath("$.winningVersion").value(2))
                .andExpect(jsonPath("$.rejectionType").value(
                        org.hamcrest.Matchers.containsString("OptimisticLocking")))
                .andExpect(jsonPath("$.surfacedAs").value(
                        org.hamcrest.Matchers.containsString("409")));

        mockMvc.perform(get("/api/demo/jpa/probe/locking?surface=envelope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(jsonPath("$.error.requestId").exists())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("stale writer")));
    }

    // ---- pagination: metadata, the count cost, two projections ----

    @Test
    @Order(6)
    void paginationServesPageMetadataOverTheTwelveSeeds() throws Exception {
        mockMvc.perform(get("/api/demo/jpa/probe/pagination?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.rows.length()").value(10))
                .andExpect(jsonPath("$.queriesIssued").value(2))
                .andExpect(jsonPath("$.projection").value(
                        org.hamcrest.Matchers.containsString("specification")));

        mockMvc.perform(get("/api/demo/jpa/probe/pagination?page=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.rows.length()").value(2));

        mockMvc.perform(get("/api/demo/jpa/probe/pagination?status=draft&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.projection").value(
                        org.hamcrest.Matchers.containsString("status=draft")));

        mockMvc.perform(get("/api/demo/jpa/probe/pagination?projection=class&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.projection").value(
                        org.hamcrest.Matchers.containsString("class")));
    }

    // ---- validation paths stay in the one taxonomy ----

    @Test
    @Order(7)
    void unknownModeAndUnboundedSizeAreValidationFailures() throws Exception {
        mockMvc.perform(get("/api/demo/jpa/probe?mode=magic&size=5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(get("/api/demo/jpa/probe?mode=batch&size=99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- zero residue: the probes roll everything back ----

    @Test
    @Order(8)
    void theProbesLeaveNoSpecimenRowsBehind() {
        Integer postsCount = jdbc.queryForObject("select count(*) from posts", Integer.class);
        Integer probeAuditRows = jdbc.queryForObject(
                "select count(*) from audit_log where actor = 'jpa-probe'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(12, postsCount,
                "specimens were rolled back — exactly the twelve seeds remain");
        org.junit.jupiter.api.Assertions.assertEquals(0, probeAuditRows,
                "the locking specimen's audit cleanup left nothing either");
    }

    private void activateLive() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"live\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("live"));
    }
}
