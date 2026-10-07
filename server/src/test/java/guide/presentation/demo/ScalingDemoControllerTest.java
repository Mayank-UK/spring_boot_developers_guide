package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ScalingDemoConfig;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the scaling:stateless demo with the topic's own wiring.
 * Every test RESETS first (the probe is a singleton with real state —
 * context caching across test methods is exactly the per-instance memory
 * the topic teaches about), then drives a deterministic request
 * sequence. Pinned facts: round-robin alternates personas, the external
 * store answers identically from any persona (one monotonic truth), a
 * local-memory value LOSES history the moment routing changes
 * (lostIfLocal is the measured proof), the process identity is the real
 * pid@host — and bad input is the typed 400 envelope, never a 500.
 */
@WebMvcTest(ScalingDemoController.class)
@Import(ScalingDemoConfig.class)
class ScalingDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void roundRobinRoutingSharesOneExternalTruth() throws Exception {
        reset();
        for (int expected = 1; expected <= 4; expected++) {
            mockMvc.perform(get("/api/demo/scaling/stateless"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reportedValue").value(expected))
                    .andExpect(jsonPath("$.externalState").value(expected))
                    .andExpect(jsonPath("$.totalRequests").value(expected))
                    .andExpect(jsonPath("$.stateHome").value("external"))
                    .andExpect(jsonPath("$.routing").value("round-robin"));
        }
        // alternation: requests 1..4 land a, b, a, b — both personas saw two
        mockMvc.perform(get("/api/demo/scaling/stateless"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routedTo").value("server-a"))
                .andExpect(jsonPath("$.instances[0].id").value("server-a"))
                .andExpect(jsonPath("$.instances[0].localState").value(3))
                .andExpect(jsonPath("$.instances[1].id").value("server-b"))
                .andExpect(jsonPath("$.instances[1].localState").value(2))
                .andExpect(jsonPath("$.externalState").value(5));
    }

    @Test
    void externalStoreAgreesFromAnyReplicaInAnyOrder() throws Exception {
        reset();
        mockMvc.perform(get("/api/demo/scaling/stateless?instance=a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routedTo").value("server-a"))
                .andExpect(jsonPath("$.routing").value("sticky"))
                .andExpect(jsonPath("$.reportedValue").value(1));
        mockMvc.perform(get("/api/demo/scaling/stateless?instance=a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportedValue").value(2))
                .andExpect(jsonPath("$.instances[0].localState").value(2))
                .andExpect(jsonPath("$.instances[1].localState").value(0));
        // the other replica answers from the SAME shared value — no catch-up, no loss
        mockMvc.perform(get("/api/demo/scaling/stateless?instance=b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportedValue").value(3))
                .andExpect(jsonPath("$.externalState").value(3))
                .andExpect(jsonPath("$.lostIfLocal").value(2))
                .andExpect(jsonPath("$.reading[1]")
                        .value(Matchers.containsString("same monotonic answer")));
    }

    @Test
    void localMemoryLosesHistoryTheMomentRoutingChanges() throws Exception {
        reset();
        for (int expected = 1; expected <= 3; expected++) {
            mockMvc.perform(get("/api/demo/scaling/stateless?store=local&instance=a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reportedValue").value(expected))
                    .andExpect(jsonPath("$.externalState").value(0));
        }
        // fourth request, other persona: told 1 — three requests of history gone
        mockMvc.perform(get("/api/demo/scaling/stateless?store=local&instance=b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportedValue").value(1))
                .andExpect(jsonPath("$.totalRequests").value(4))
                .andExpect(jsonPath("$.lostIfLocal").value(3))
                .andExpect(jsonPath("$.stateHome").value("local"))
                .andExpect(jsonPath("$.reading[1]")
                        .value(Matchers.containsString("routing just forgot")))
                .andExpect(jsonPath("$.reading[1]")
                        .value(Matchers.containsString("3")));
    }

    @Test
    void theInstanceIdentityIsTheRealProcessNotAClaim() throws Exception {
        reset();
        mockMvc.perform(get("/api/demo/scaling/stateless"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processInstanceId")
                        .value(Matchers.matchesPattern("\\d+@.+")))
                .andExpect(jsonPath("$.processStartedAt").isNotEmpty())
                .andExpect(jsonPath("$.reading[3]")
                        .value(Matchers.containsString("simulated inside this one JVM")))
                .andExpect(jsonPath("$.reading[3]")
                        .value(Matchers.containsString("redis or PostgreSQL")));
    }

    @Test
    void resetZeroesTheFleetAndRewindsTheRouter() throws Exception {
        mockMvc.perform(get("/api/demo/scaling/stateless?instance=a"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/scaling/stateless/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(0))
                .andExpect(jsonPath("$.externalState").value(0))
                .andExpect(jsonPath("$.reportedValue").value(0))
                .andExpect(jsonPath("$.routedTo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.instances.length()").value(2))
                .andExpect(jsonPath("$.instances[0].localState").value(0))
                .andExpect(jsonPath("$.instances[1].localState").value(0))
                .andExpect(jsonPath("$.reading[0]")
                        .value(Matchers.containsString("reset")));
        // cursor rewound: the next round-robin request lands on server-a again
        mockMvc.perform(get("/api/demo/scaling/stateless"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routedTo").value("server-a"))
                .andExpect(jsonPath("$.reportedValue").value(1));
    }

    @Test
    void badInputIsTheTypedValidationEnvelopeNeverA500() throws Exception {
        mockMvc.perform(get("/api/demo/scaling/stateless?instance=huge"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(Matchers.containsString("instance must be one of")));
        mockMvc.perform(get("/api/demo/scaling/stateless?store=banana"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(Matchers.containsString("store must be one of")));
    }

    private void reset() throws Exception {
        mockMvc.perform(get("/api/demo/scaling/stateless/reset")).andExpect(status().isOk());
    }
}
