package guide.data.caching;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * THE OPTIONAL-SERVICE CONTRACT for the caching area, executable: the FULL
 * application context boots — with Redis pointed at a port where nothing
 * listens (6399, the deterministic trick topic 20 established) — and then:
 *
 * - fake mode serves both caching paths (the app never needed the server to
 *   boot or to work — and NOTHING Redis-shaped was constructed at boot: the
 *   live adapter still sits behind its @Lazy seam);
 * - the PUBLIC scenario flip (PATCH /api/demo/scenario) turns cached reads
 *   into the typed 503 service_unavailable envelope naming the area —
 *   classified by the router at the port's edge, never a stack trace, never
 *   a boot failure, and the annotation path degrades identically (the same
 *   router serves its CacheManager);
 * - flipping back restores service on the very next call.
 */
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
@AutoConfigureMockMvc
class CachingOptionalServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    @AfterEach
    void scenarioBackToDefaults() {
        scenarioStore.reset();
    }

    @Test
    void fakeModeServesBothPathsWithoutRedisEverExisting() throws Exception {
        mockMvc.perform(get("/api/demo/caching/stats/rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.backendMode").value("fake"));
        mockMvc.perform(get("/api/demo/caching/stats/rest?mode=annotation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backendMode").value("fake"));
    }

    @Test
    void thePublicFlipDegradesBothPathsIntoTheTyped503AndBack() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"live\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/demo/caching/stats/rest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.area").value("caching"));
        mockMvc.perform(get("/api/demo/caching/stats/rest?mode=annotation"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"));

        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"fake\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/caching/stats/rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backendMode").value("fake"));
    }

    @Test
    void anAreaPinBeatsTheGlobalModeForThisAreaOnly() throws Exception {
        scenarioStore.setDataMode(ScenarioRoutingCacheBackend.AREA, ScenarioDataMode.FAKE);
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"live\"}"))
                .andExpect(status().isOk());

        // the caching area's own pin wins over the global flip
        mockMvc.perform(get("/api/demo/caching/stats/rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backendMode").value("fake"));
    }
}
