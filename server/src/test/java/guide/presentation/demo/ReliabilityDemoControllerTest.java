package guide.presentation.demo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ReliabilityDemoConfig;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the reliability:breaker theater — every endpoint, every
 * state transition, ZERO wall-clock waits. The theater's clock is virtual
 * and the breaker is driven with exact arithmetic: the minimum-call window
 * (4), the trip to OPEN, the rejection at the door, the forced HALF_OPEN
 * probe and the healthy probe that closes it again. The timelines asserted
 * here (150ms healthy, 1320ms flaky-with-backoff, 3090ms down, 4500ms
 * slow) are reproducible byte-for-byte because nothing sleeps.
 */
@WebMvcTest(ReliabilityDemoController.class)
@Import(ReliabilityDemoConfig.class)
class ReliabilityDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store ReliabilityDemoConfig needs comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @BeforeEach
    void resetTheater() throws Exception {
        scenarioStore.reset();
        mockMvc.perform(post("/api/demo/reliability/reset")).andExpect(status().isOk());
    }

    @AfterEach
    void clearInjectedFailure() {
        scenarioStore.reset();
    }

    @Test
    void defaultStatusIsClosedHealthyAndFullyDescribed() throws Exception {
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.downstreamMode").value("healthy"))
                .andExpect(jsonPath("$.injectFailureRail").value(false))
                .andExpect(jsonPath("$.virtualNowMs").value(0))
                .andExpect(jsonPath("$.config.failureRateThreshold").value(50.0))
                .andExpect(jsonPath("$.config.minimumNumberOfCalls").value(4))
                .andExpect(jsonPath("$.config.waitDurationMs").value(10000))
                .andExpect(jsonPath("$.config.slidingWindowSize").value(10))
                .andExpect(jsonPath("$.config.permittedInHalfOpen").value(1))
                .andExpect(jsonPath("$.metrics.successes").value(0))
                .andExpect(jsonPath("$.metrics.failures").value(0))
                .andExpect(jsonPath("$.metrics.failureRate").value(nullValue()))
                .andExpect(jsonPath("$.openedAtMs").value(nullValue()))
                .andExpect(jsonPath("$.probeEligibleAtMs").value(nullValue()))
                .andExpect(jsonPath("$.reading[0]").value(containsString("CLOSED")))
                .andExpect(jsonPath("$.reading[0]").value(containsString("starts judging at 4")))
                .andExpect(jsonPath("$.reading[1]").value(containsString("trip condition")));
    }

    @Test
    void healthyCallSucceedsOnTheFirstAttempt() throws Exception {
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].attempt").value(1))
                .andExpect(jsonPath("$.attempts[0].outcome").value("success"))
                .andExpect(jsonPath("$.attempts[0].startedAtMs").value(0))
                .andExpect(jsonPath("$.attempts[0].endedAtMs").value(150))
                .andExpect(jsonPath("$.totalElapsedMs").value(150))
                .andExpect(jsonPath("$.downstreamAttempts").value(1))
                .andExpect(jsonPath("$.breakerStateBefore").value("CLOSED"))
                .andExpect(jsonPath("$.breakerStateAfter").value("CLOSED"));
    }

    @Test
    void flakyCallsSucceedBecauseRetriesAbsorbTheTransientFailure() throws Exception {
        mockMvc.perform(patch("/api/demo/reliability/dial?mode=flaky"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("flaky"));
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].outcome").value("failed"))
                .andExpect(jsonPath("$.attempts[0].detail").value(containsString("503")))
                .andExpect(jsonPath("$.attempts[1].outcome").value("success"))
                .andExpect(jsonPath("$.totalElapsedMs").value(1320));
        // the breaker never learned the flake happened — retries hid it
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.metrics.successes").value(1))
                .andExpect(jsonPath("$.metrics.failures").value(0));
    }

    @Test
    void downDialTripsTheBreakerOpenOnlyAfterTheMinimumWindow() throws Exception {
        mockMvc.perform(patch("/api/demo/reliability/dial?mode=down"))
                .andExpect(jsonPath("$").value("down"));
        for (int callsMade = 1; callsMade <= 3; callsMade++) {
            mockMvc.perform(post("/api/demo/reliability/call"))
                    .andExpect(jsonPath("$.outcome").value("FAILED"))
                    .andExpect(jsonPath("$.attempts.length()").value(3))
                    .andExpect(jsonPath("$.totalElapsedMs").value(3090))
                    .andExpect(jsonPath("$.breakerStateAfter").value("CLOSED"));
        }
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.metrics.failures").value(3))
                .andExpect(jsonPath("$.metrics.failureRate").value(nullValue()));
        // the 4th failure fills the minimum window: 100% >= 50% trips OPEN
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(jsonPath("$.breakerStateBefore").value("CLOSED"))
                .andExpect(jsonPath("$.breakerStateAfter").value("OPEN"));
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.metrics.failures").value(4))
                .andExpect(jsonPath("$.metrics.failureRate").value(100.0))
                .andExpect(jsonPath("$.openedAtMs").value(4 * 3090))
                .andExpect(jsonPath("$.probeEligibleAtMs").value(4 * 3090 + 10000))
                .andExpect(jsonPath("$.reading[0]").value(containsString("OPEN since t=")));
    }

    @Test
    void anOpenBreakerRejectsCallsWithoutTouchingTheDownstream() throws Exception {
        tripTheBreakerOpen();
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED_BY_BREAKER"))
                .andExpect(jsonPath("$.attempts.length()").value(0))
                .andExpect(jsonPath("$.downstreamAttempts").value(0))
                .andExpect(jsonPath("$.totalElapsedMs").value(0))
                .andExpect(jsonPath("$.breakerStateBefore").value("OPEN"))
                .andExpect(jsonPath("$.breakerStateAfter").value("OPEN"));
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.metrics.notPermitted").value(1));
    }

    @Test
    void forcedProbeAndAHealthyProbeCloseTheBreakerAgain() throws Exception {
        tripTheBreakerOpen();
        mockMvc.perform(post("/api/demo/reliability/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("HALF_OPEN"));
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("HALF_OPEN"))
                .andExpect(jsonPath("$.reading[0]").value(containsString("HALF_OPEN")));
        mockMvc.perform(patch("/api/demo/reliability/dial?mode=healthy"))
                .andExpect(jsonPath("$").value("healthy"));
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.breakerStateBefore").value("HALF_OPEN"))
                .andExpect(jsonPath("$.breakerStateAfter").value("CLOSED"));
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.openedAtMs").value(nullValue()));
    }

    @Test
    void aFailingProbeReopensTheBreaker() throws Exception {
        tripTheBreakerOpen();
        mockMvc.perform(post("/api/demo/reliability/probe"))
                .andExpect(jsonPath("$").value("HALF_OPEN"));
        // dial is still down — the single permitted probe fails
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.breakerStateBefore").value("HALF_OPEN"))
                .andExpect(jsonPath("$.breakerStateAfter").value("OPEN"));
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.openedAtMs").value(4 * 3090 + 3090));
    }

    @Test
    void slowDialTimesOutEveryAttemptAndTheBackoffCurveIsExact() throws Exception {
        mockMvc.perform(patch("/api/demo/reliability/dial?mode=slow"))
                .andExpect(jsonPath("$").value("slow"));
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(jsonPath("$.outcome").value("TIMED_OUT"))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].outcome").value("timed_out"))
                .andExpect(jsonPath("$.attempts[0].startedAtMs").value(0))
                .andExpect(jsonPath("$.attempts[0].endedAtMs").value(500))
                .andExpect(jsonPath("$.attempts[1].startedAtMs").value(1500))
                .andExpect(jsonPath("$.attempts[1].endedAtMs").value(2000))
                .andExpect(jsonPath("$.attempts[2].startedAtMs").value(4000))
                .andExpect(jsonPath("$.attempts[2].endedAtMs").value(4500))
                .andExpect(jsonPath("$.totalElapsedMs").value(4500));
    }

    @Test
    void theGlobalScenarioRailFailsEveryAttemptRegardlessOfTheDial() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));
        mockMvc.perform(post("/api/demo/reliability/call"))
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].detail")
                        .value(containsString("global failure rail")));
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.injectFailureRail").value(true))
                .andExpect(jsonPath("$.reading[2]").value(containsString("injectFailure rail is ON")));
    }

    @Test
    void badDialInputIsTheTypedValidationEnvelopeNeverA500() throws Exception {
        mockMvc.perform(patch("/api/demo/reliability/dial?mode=exploded"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value(containsString("mode must be one of")));
        mockMvc.perform(patch("/api/demo/reliability/dial"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void forcingAProbeWithoutAnOpenBreakerIsTheTypedEnvelope() throws Exception {
        mockMvc.perform(post("/api/demo/reliability/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value(containsString("nothing to probe")));
    }

    @Test
    void resetReturnsAFreshClosedTheater() throws Exception {
        tripTheBreakerOpen();
        mockMvc.perform(post("/api/demo/reliability/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.downstreamMode").value("healthy"))
                .andExpect(jsonPath("$.virtualNowMs").value(0))
                .andExpect(jsonPath("$.metrics.failures").value(0))
                .andExpect(jsonPath("$.recentCalls.length()").value(0));
    }

    /** Dial down, four failing calls — the deterministic door to OPEN. */
    private void tripTheBreakerOpen() throws Exception {
        mockMvc.perform(patch("/api/demo/reliability/dial?mode=down")).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/demo/reliability/call")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/demo/reliability/breaker"))
                .andExpect(jsonPath("$.state").value("OPEN"));
    }
}
