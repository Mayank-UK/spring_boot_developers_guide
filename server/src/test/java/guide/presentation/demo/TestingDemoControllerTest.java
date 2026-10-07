package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.TestingDemoConfig;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the runner demo with the topic's own wiring. The slice has
 * no GuideConfig and NO live web server, which is half the teaching: the
 * posts substrate falls back to the fake seed (so the unit and data checks
 * PASS on real use cases), and the web check reports SKIPPED — absent
 * machinery reported, never faked. Pinned here: the report's shape (five
 * checks, runner-style fields), the default tally (3 passed / 0 failed /
 * 2 skipped), and the failure-injection exhibit — with the scenario switch
 * ON, the deliberately wrong assertion FAILS and the tally says so.
 */
@WebMvcTest(TestingDemoController.class)
@Import(TestingDemoConfig.class)
class TestingDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store the demo reads comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @AfterEach
    void clearInjectedFailure() {
        scenarioStore.reset();
    }

    @Test
    void theBatteryRunsItsLayersAndReportsTheRunnerShape() throws Exception {
        mockMvc.perform(get("/api/demo/testing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureInjected").value(false))
                .andExpect(jsonPath("$.passed").value(3))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.totalDurationMs").isNumber())
                .andExpect(jsonPath("$.checks.length()").value(5))
                .andExpect(jsonPath("$.checks[0].layer").value("unit"))
                .andExpect(jsonPath("$.checks[0].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[0].durationMs").isNumber())
                .andExpect(jsonPath("$.checks[0].detail").isNotEmpty())
                .andExpect(jsonPath("$.checks[2].layer").value("data"))
                .andExpect(jsonPath("$.checks[2].status").value("PASSED"));
    }

    @Test
    void theWebCheckSkipsHonestlyWhereNoLiveServerExists() throws Exception {
        mockMvc.perform(get("/api/demo/testing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[3].layer").value("web"))
                .andExpect(jsonPath("$.checks[3].status").value("SKIPPED"))
                .andExpect(jsonPath("$.checks[3].detail").value(
                        "no live web server in this context (a slice) — reported, not faked"));
    }

    @Test
    void failureInjectionTurnsTheWrongAssertionIntoARedCheck() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));

        mockMvc.perform(get("/api/demo/testing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureInjected").value(true))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.checks[4].layer").value("injected"))
                .andExpect(jsonPath("$.checks[4].status").value("FAILED"))
                .andExpect(jsonPath("$.checks[4].detail").value(
                        "expected 2 + 2 = 5 — the assertion is wrong on purpose (failure injection is ON)"));
    }

    @Test
    void withInjectionOffTheInjectedCheckSkipsWithItsReason() throws Exception {
        mockMvc.perform(get("/api/demo/testing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[4].status").value("SKIPPED"))
                .andExpect(jsonPath("$.checks[4].detail").value(
                        "failure injection is off — flip the scenario switch to see a check fail"));
    }
}
