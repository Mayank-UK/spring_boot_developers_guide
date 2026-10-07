package guide.presentation.demo;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.RedisDemoConfig;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioStore;
import guide.data.redis.InMemoryRedisPlayground;
import guide.domain.redis.RecentMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the redis console with the REAL demo beans in the fake mode
 * the slice boots in (no Redis autoconfiguration exists here — which is
 * itself the discipline under test): every op family round-trips through
 * the HTTP surface with the namespaced key and mode on every answer, the
 * ttl op serves the typed three-way insight, publish feeds the recent ring,
 * unknown ops and missing operands are the validation envelope, and the
 * scenario's injectFailure exhibit answers the typed 503. The fake and the
 * ring are reset before every test, so nothing depends on order.
 */
@WebMvcTest(RedisDemoController.class)
@Import(RedisDemoConfig.class)
class RedisDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryRedisPlayground fake;

    @Autowired
    private RecentMessages recent;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store RedisDemoConfig needs comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @BeforeEach
    void resetDemoSurfaces() {
        fake.clear();
        recent.clear();
        scenarioStore.reset();
    }

    @AfterEach
    void clearInjectedFailure() {
        scenarioStore.reset();
    }

    private static String opBody(String op, String key, String value, String field,
            Double score, Long ttlSeconds) {
        StringBuilder json = new StringBuilder("{\"op\":\"" + op + "\",\"key\":\"" + key + "\"");
        if (value != null) {
            json.append(",\"value\":\"").append(value).append("\"");
        }
        if (field != null) {
            json.append(",\"field\":\"").append(field).append("\"");
        }
        if (score != null) {
            json.append(",\"score\":").append(score);
        }
        if (ttlSeconds != null) {
            json.append(",\"ttlSeconds\":").append(ttlSeconds);
        }
        return json.append("}").toString();
    }

    @Test
    void setCarriesTheNamespacedKeyAndModeOnEveryAnswer() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("set", "greeting", "hello", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.namespacedKey").value("guide:demo:greeting"))
                .andExpect(jsonPath("$.mode").value("fake"));
    }

    @Test
    void hashObjectRoundTripsThroughTheConsole() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("hset", "profile", "Ada", "name", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespacedKey").value("guide:demo:profile"));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("hgetall", "profile", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.name").value("Ada"));
    }

    @Test
    void listFeedAndSortedSetLeaderboardReadBackInOrder() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("lpush", "feed", "first", null, null, null)));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("lpush", "feed", "second", null, null, null)));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("lrange", "feed", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", hasSize(2)))
                .andExpect(jsonPath("$.result[0]").value("second"));

        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("zadd", "league", "ada", null, 10.0, null)));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("zadd", "league", "grace", null, 12.0, null)));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("zadd", "league", "knuth", null, 9.0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(1));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("zrange", "league", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].member").value("knuth"))
                .andExpect(jsonPath("$.result[2].member").value("grace"));
    }

    @Test
    void ttlLifecycleIsDeterministicWithoutSleeping() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("set", "session", "blob", null, null, 60L)));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("ttl", "session", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.exists").value(true))
                .andExpect(jsonPath("$.result.hasExpiry").value(true))
                .andExpect(jsonPath("$.result.secondsRemaining").value(60));

        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("set", "plain", "v", null, null, null)));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("ttl", "plain", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.exists").value(true))
                .andExpect(jsonPath("$.result.hasExpiry").value(false));

        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("ttl", "ghost", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.exists").value(false));
    }

    @Test
    void publishFeedsTheRecentRingAndRecentServesIt() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("publish", "chan:playground", "hello from mock", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespacedKey").value("guide:demo:chan:playground"))
                .andExpect(jsonPath("$.result.channel").value("guide:demo:chan:playground"))
                .andExpect(jsonPath("$.result.receivers").value(1));

        mockMvc.perform(get("/api/demo/redis/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("redis"))
                .andExpect(jsonPath("$.mode").value("fake"))
                .andExpect(jsonPath("$.messages[0].channel").value("guide:demo:chan:playground"))
                .andExpect(jsonPath("$.messages[0].message").value("hello from mock"));
    }

    @Test
    void modeEndpointReportsTheAreaHonestly() throws Exception {
        mockMvc.perform(get("/api/demo/redis/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("redis"))
                .andExpect(jsonPath("$.dataMode").value("fake"));
    }

    @Test
    void unknownOpsAndMissingOperandsAreValidationEnvelopes() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"op\":\"flushall\",\"key\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"op\":\"set\",\"key\":\"k\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.fields.value").value(
                        org.hamcrest.Matchers.containsString("required")));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"op\":\"publish\",\"key\":\"outside\",\"value\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void injectedFailureAnswersTheTypedUnavailableEnvelope() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(opBody("get", "k", null, null, null, null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.area").value("redis"));
        mockMvc.perform(get("/api/demo/redis/recent"))
                .andExpect(status().isServiceUnavailable());
    }
}
