package guide.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScenarioController.class)
class ScenarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @AfterEach
    void resetStore() {
        scenarioStore.reset();
    }

    @Test
    void getServesTheInitialState() throws Exception {
        mockMvc.perform(get("/api/demo/scenario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.injectFailure").value(false));
    }

    @Test
    void patchRoundtripsPartialUpdatesAndReflectedByGet() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"injectFailure\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.injectFailure").value(true));

        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"live\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("live"))
                .andExpect(jsonPath("$.injectFailure").value(true));

        mockMvc.perform(get("/api/demo/scenario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("live"))
                .andExpect(jsonPath("$.injectFailure").value(true));
    }

    @Test
    void invalidPatchValuesYieldTheValidationEnvelopeWithFieldReasons() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"staging\",\"bogus\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.fields.dataMode").exists())
                .andExpect(jsonPath("$.error.fields.bogus").exists());

        // A JSON null survives the field check and must still fail validation.
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"injectFailure\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        // Non-object bodies are rejected before any field is read.
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.fields.body").exists());
    }

    @Test
    void malformedJsonBodyYieldsTheValidationEnvelope() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }
}
