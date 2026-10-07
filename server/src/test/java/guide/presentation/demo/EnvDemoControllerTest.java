package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.EnvironmentsDemoConfig;
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
 * MockMvc over the env inspector demo with the topic's own wiring — the
 * slice is the whole teaching: no profile is active (so the inspector
 * reports Spring's literal "default"), the guide.env.* values bind from
 * application.yml's env-var defaults (GUIDE_* unset in the test container),
 * and the record's constructor has already normalized them. Pinned: the
 * report's shape (four property lines, safe trio + ONE redacted secret),
 * the secret's value never traveling (only the dev-default verdict), the
 * two runtime flags, and — the feature-flags proof — a store PATCH changes
 * the flag line while every config line stays frozen.
 */
@WebMvcTest(EnvDemoController.class)
@Import(EnvironmentsDemoConfig.class)
class EnvDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store the inspector reads comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @AfterEach
    void resetScenario() {
        scenarioStore.reset();
    }

    @Test
    void theInspectorReportsTheDeterministicEnvironmentStory() throws Exception {
        mockMvc.perform(get("/api/demo/env/inspector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfiles.length()").value(1))
                .andExpect(jsonPath("$.activeProfiles[0]").value("default"))
                .andExpect(jsonPath("$.properties.length()").value(4))
                .andExpect(jsonPath("$.properties[0].key").value("guide.env.deploy-environment"))
                .andExpect(jsonPath("$.properties[0].value").value("local"))
                .andExpect(jsonPath("$.properties[0].classification").value("safe"))
                .andExpect(jsonPath("$.properties[1].key").value("guide.env.region"))
                .andExpect(jsonPath("$.properties[1].value").value("local"))
                .andExpect(jsonPath("$.properties[2].key").value("guide.env.maintenance-mode"))
                .andExpect(jsonPath("$.properties[2].value").value("false"))
                .andExpect(jsonPath("$.reading").isArray())
                .andExpect(jsonPath("$.reading.length()").value(4));
    }

    @Test
    void theSecretValueNeverTravelsOnlyItsShapeDoes() throws Exception {
        mockMvc.perform(get("/api/demo/env/inspector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[3].key").value("guide.env.demo-api-key"))
                .andExpect(jsonPath("$.properties[3].value").value("<redacted>"))
                .andExpect(jsonPath("$.properties[3].classification").value("secret"))
                .andExpect(jsonPath("$.demoSecretIsDevDefault").value(true))
                // the fake-by-construction default never appears anywhere in the answer
                .andExpect(jsonPath("$.reading[2]")
                        .value(org.hamcrest.Matchers.containsString("no path into this")))
                .andExpect(jsonPath("$.reading[2]")
                        .value(org.hamcrest.Matchers.containsString("DEV DEFAULT")));
    }

    @Test
    void theRuntimeFlagsAreReportedBesideTheConfig() throws Exception {
        mockMvc.perform(get("/api/demo/env/inspector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.length()").value(2))
                .andExpect(jsonPath("$.flags[0].name").value("scenario.data-mode"))
                .andExpect(jsonPath("$.flags[0].state").value("fake"))
                .andExpect(jsonPath("$.flags[0].runtimeSwitchable").value(true))
                .andExpect(jsonPath("$.flags[0].note")
                        .value(org.hamcrest.Matchers.containsString("/api/demo/scenario")))
                .andExpect(jsonPath("$.flags[1].name").value("scenario.inject-failure"))
                .andExpect(jsonPath("$.flags[1].state").value("false"));
    }

    @Test
    void aScenarioPatchMovesTheFlagWhileTheConfigStaysFrozen() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));

        mockMvc.perform(get("/api/demo/env/inspector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[0].state").value("fake"))
                .andExpect(jsonPath("$.flags[1].state").value("true"))
                // boot-shaped config unchanged by the runtime flip
                .andExpect(jsonPath("$.properties[0].value").value("local"))
                .andExpect(jsonPath("$.properties[3].value").value("<redacted>"));
    }
}
