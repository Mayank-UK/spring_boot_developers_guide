package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.BestPracticesDemoConfig;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the best:checklist demo with the topic's own wiring — the
 * slice IS the determinism proof: every live verdict reads state that exists
 * before the first request (the FailureKind enum, the ContentRegistry
 * constructed at wiring time, the application.yml on the test classpath, the
 * bound AppConfig), so the same request must answer the same way every run.
 * Pinned: the counts (6 live, all passing; 10 verify pointers — a checklist
 * that claims passes it cannot see is the anti-pattern the topic teaches),
 * the shape of every category, one live verdict's evidence read from the
 * REAL taxonomy, one property verdict from the REAL config, and one verify
 * row carrying a runnable command.
 */
@WebMvcTest(BestPracticesDemoController.class)
@Import(BestPracticesDemoConfig.class)
class BestPracticesDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theChecklistGradesItselfWithExactCounts() throws Exception {
        mockMvc.perform(get("/api/demo/best/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(16))
                .andExpect(jsonPath("$.counts.liveChecks").value(6))
                .andExpect(jsonPath("$.counts.livePasses").value(6))
                .andExpect(jsonPath("$.counts.verifyPointers").value(10))
                .andExpect(jsonPath("$.reading.length()").value(3))
                .andExpect(jsonPath("$.reading[0]")
                        .value(Matchers.containsString("6 of 6 live checks pass")))
                .andExpect(jsonPath("$.reading[1]")
                        .value(Matchers.containsString("VERIFY by design")));
    }

    @Test
    void everyItemCarriesCategoryIdTitleAndOneOfTheTwoVerdicts() throws Exception {
        mockMvc.perform(get("/api/demo/best/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].category").value("setup"))
                .andExpect(jsonPath("$.items[0].id").value("typed-config"))
                .andExpect(jsonPath("$.items[0].verdict").value("pass"))
                .andExpect(jsonPath("$.items[0].evidence")
                        .value(Matchers.containsString("deploy-environment")))
                .andExpect(jsonPath("$.items[*].verdict")
                        .value(Matchers.everyItem(
                                Matchers.anyOf(Matchers.is("pass"), Matchers.is("verify")))))
                .andExpect(jsonPath("$.items[*].category")
                        .value(Matchers.everyItem(Matchers.isOneOf(
                                "setup", "api", "security", "data", "reliability",
                                "observability", "performance", "operations", "process", "humans"))));
    }

    @Test
    void theTaxonomyVerdictIsReadFromTheRealEnumNotAHardcodedCopy() throws Exception {
        mockMvc.perform(get("/api/demo/best/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == 'one-error-envelope')].verdict")
                        .value(Matchers.hasItem("pass")))
                .andExpect(jsonPath("$.items[?(@.id == 'one-error-envelope')].evidence")
                        .value(Matchers.hasItem(Matchers.allOf(
                                Matchers.containsString("8 FailureKinds"),
                                Matchers.containsString("validation_failed"),
                                Matchers.containsString("internal_error")))));
    }

    @Test
    void thePropertyVerdictsReadTheEffectiveConfiguration() throws Exception {
        mockMvc.perform(get("/api/demo/best/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == 'virtual-threads')].verdict")
                        .value(Matchers.hasItem("pass")))
                .andExpect(jsonPath("$.items[?(@.id == 'virtual-threads')].evidence")
                        .value(Matchers.hasItem(
                                Matchers.containsString("spring.threads.virtual.enabled=true"))))
                .andExpect(jsonPath("$.items[?(@.id == 'graceful-shutdown')].verdict")
                        .value(Matchers.hasItem("pass")))
                .andExpect(jsonPath("$.items[?(@.id == 'graceful-shutdown')].evidence")
                        .value(Matchers.hasItem(
                                Matchers.containsString("server.shutdown=graceful"))))
                .andExpect(jsonPath("$.items[?(@.id == 'content-registry')].verdict")
                        .value(Matchers.hasItem("pass")))
                .andExpect(jsonPath("$.items[?(@.id == 'content-registry')].evidence")
                        .value(Matchers.hasItem(
                                Matchers.containsString("topics constructed into a ContentRegistry"))))
                .andExpect(jsonPath("$.items[?(@.id == 'probes-and-metrics')].verdict")
                        .value(Matchers.hasItem("pass")))
                .andExpect(jsonPath("$.items[?(@.id == 'probes-and-metrics')].evidence")
                        .value(Matchers.hasItem(
                                Matchers.containsString("management.endpoint.health.probes.enabled=true"))));
    }

    @Test
    void verifyRowsCarryTheCurlNeverAClaimedPass() throws Exception {
        mockMvc.perform(get("/api/demo/best/checklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == 'parameterized-sql')].verdict")
                        .value(Matchers.hasItem("verify")))
                .andExpect(jsonPath("$.items[?(@.id == 'parameterized-sql')].verify")
                        .value(Matchers.hasItem(
                                Matchers.containsString("curl -s -X POST localhost:8080/api/demo/db/sql/safety"))))
                .andExpect(jsonPath("$.items[?(@.id == 'suite-green')].verify")
                        .value(Matchers.hasItem(Matchers.containsString("mvn -q test"))));
    }
}
