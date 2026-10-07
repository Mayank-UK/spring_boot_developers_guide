package guide.data.redis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * THE OPTIONAL-SERVICE CONTRACT, executable: the FULL application context
 * boots — with Redis pointed at a port where nothing listens (6399), on
 * purpose, so the test stays deterministic even on a machine whose 6379 is
 * occupied — and then:
 *
 * - fake mode serves every op (the app never needed the server to boot or
 *   to work);
 * - the PUBLIC scenario flip (PATCH /api/demo/scenario, the same endpoint a
 *   reader drives) turns ops into the typed 503 service_unavailable
 *   envelope naming the area — classified by LiveRedisArea, never a stack
 *   trace, never a boot failure;
 * - flipping back restores service on the very next call (the failed
 *   activation left its flag down, so nothing is wedged).
 *
 * This is the sequence the topic's discipline section narrates; here it is
 * a green test instead of a promise.
 */
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
@AutoConfigureMockMvc
class RedisOptionalServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    @AfterEach
    void scenarioBackToDefaults() {
        scenarioStore.reset();
    }

    private static String opBody(String op, String key, String value) {
        return "{\"op\":\"" + op + "\",\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
    }

    @Test
    void theApplicationContextBootsAndServesFakeModeWithoutRedis() throws Exception {
        mockMvc.perform(get("/api/demo/redis/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("redis"))
                .andExpect(jsonPath("$.dataMode").value("fake"));

        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("set", "boot-proof", "served")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.namespacedKey").value("guide:demo:boot-proof"))
                .andExpect(jsonPath("$.mode").value("fake"));
    }

    @Test
    void flippingLiveWithTheServerAbsentAnswersTheTypedUnavailableEnvelope() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"live\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("get", "boot-proof", "x")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("Redis is unreachable")))
                .andExpect(jsonPath("$.error.area").value("redis"));

        mockMvc.perform(get("/api/demo/redis/mode"))
                .andExpect(status().isOk())
                // the mode itself is honest — the SERVER is what is missing
                .andExpect(jsonPath("$.dataMode").value("live"));
    }

    @Test
    void flippingBackRestoresServiceOnTheVeryNextCall() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"live\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("set", "wedged", "no")))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(patch("/api/demo/scenario").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\":\"fake\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("set", "recovered", "yes")))
                .andExpect(status().isOk())
                // the failed activation left nothing wedged
                .andExpect(jsonPath("$.mode").value("fake"));
    }

    @Test
    void fakePubSubKeepsWorkingWhileTheServerIsAbsent() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("publish", "chan:playground", "offline but serving")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.channel").value("guide:demo:chan:playground"));

        mockMvc.perform(get("/api/demo/redis/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].message").value("offline but serving"));
    }
}
