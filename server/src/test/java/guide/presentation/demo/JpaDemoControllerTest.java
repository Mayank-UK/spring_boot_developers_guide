package guide.presentation.demo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.UnavailableException;
import guide.domain.jpa.JpaProbePort;
import guide.domain.jpa.ReadJpaPage;
import guide.domain.jpa.RunJpaProbe;
import guide.domain.jpa.RunLockingProbe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 18's three demo endpoints with the use cases mocked:
 * the wire contracts the client demo parses (probe measurement, locking
 * story, page metadata) and — the one behavior that must NEVER depend on
 * PostgreSQL being up in a slice — the typed envelopes through the real
 * advice: the fake-mode UNAVAILABLE (503) and the locking CONFLICT (409).
 */
@WebMvcTest(JpaDemoController.class)
class JpaDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunJpaProbe probe;

    @MockitoBean
    private RunLockingProbe locking;

    @MockitoBean
    private ReadJpaPage pagination;

    @Test
    void probeServesTheMeasurementWireShape() throws Exception {
        when(probe.run("n-plus-1", 5)).thenReturn(new JpaProbePort.ProbeReport(
                "n-plus-1", 5, 6, 5, 15, 21_500_000L,
                "N+1 confirmed: 5 posts cost 6 queries (1 extra)"));

        mockMvc.perform(get("/api/demo/jpa/probe?mode=n-plus-1&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("n-plus-1"))
                .andExpect(jsonPath("$.queriesIssued").value(6))
                .andExpect(jsonPath("$.postsReturned").value(5))
                .andExpect(jsonPath("$.entriesLoaded").value(15))
                .andExpect(jsonPath("$.wallTimeNanos").value(21500000))
                .andExpect(jsonPath("$.verdict").value(org.hamcrest.Matchers.containsString("N+1")));
    }

    @Test
    void fakeModeSurfacesAsTheUnavailableEnvelope() throws Exception {
        when(probe.run(any(), any())).thenThrow(new UnavailableException(
                "the JPA probe runs only against live PostgreSQL", Map.of("area", "jpa")));

        mockMvc.perform(get("/api/demo/jpa/probe?mode=batch"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("live PostgreSQL")))
                .andExpect(jsonPath("$.error.requestId").exists());
    }

    @Test
    void lockingReportAndConflictEnvelopeBothRender() throws Exception {
        when(locking.run(null)).thenReturn(new JpaProbePort.LockingReport(
                "0f1e2d3c-0000-4000-8000-000000000000", "first writer (fresh version)", 1,
                true, "ObjectOptimisticLockingFailureException", "409 conflict envelope",
                "the same discipline as WHERE version = ?"));
        when(locking.run("envelope")).thenThrow(new guide.core.errors.ConflictException(
                "optimistic lock rejected the stale writer", Map.of("currentVersion", 1)));

        mockMvc.perform(get("/api/demo/jpa/probe/locking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staleWriterRejected").value(true))
                .andExpect(jsonPath("$.winningVersion").value(1));

        mockMvc.perform(get("/api/demo/jpa/probe/locking?surface=envelope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void paginationServesMetadataRowsAndCost() throws Exception {
        when(pagination.read(eq(0), eq(10), any(), any())).thenReturn(new JpaProbePort.PageReport(
                0, 10, 12, 2, true, false, 2, "specification (dynamic filter: none)",
                List.of(new JpaProbePort.PageRow("one", "published", 1),
                        new JpaProbePort.PageRow("two", "draft", 3))));

        mockMvc.perform(get("/api/demo/jpa/probe/pagination?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.queriesIssued").value(2))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].title").value("one"))
                .andExpect(jsonPath("$.rows[1].status").value("draft"))
                .andExpect(jsonPath("$.rows[1].version").value(3));
    }
}
