package guide.presentation.demo;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.CachingDemoConfig;
import guide.config.PostsDemoConfig;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioStore;
import guide.data.caching.InMemoryCacheBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the caching lab with the REAL demo beans in the fake mode
 * the slice boots in (no Redis anywhere in this context — which is itself
 * the optional-service discipline under test): both caching paths round
 * trip through the HTTP surface with HIT/MISS verdicts and non-colliding
 * keys, invalidation names its keys and forces the next MISS, negative
 * caching holds (the empty answer is cached), unknown modes are the
 * validation envelope, and the HTTP-caching specimen serves its validators
 * and answers a REAL If-None-Match with a 304 and no body. The @EnableCaching
 * infrastructure rides along on the imported config, so the annotation path
 * is exercised in the slice exactly as in the full app.
 */
@WebMvcTest(CachingDemoController.class)
@Import({CachingDemoConfig.class, PostsDemoConfig.class})
class CachingDemoControllerTest {

    private static final String REST = "/api/demo/caching/stats/rest";
    private static final String SEED_POST = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryCacheBackend fakeCache;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store the demo configs need comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @BeforeEach
    void resetDemoSurfaces() {
        fakeCache.clear();
        scenarioStore.reset();
    }

    @AfterEach
    void clearInjectedFailure() {
        scenarioStore.reset();
    }

    @Test
    void explicitPathMissesThenHitsWithTheComputeVerdictAndKey() throws Exception {
        mockMvc.perform(get(REST).param("mode", "explicit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.key").value("stats:rest"))
                .andExpect(jsonPath("$.mode").value("explicit"))
                .andExpect(jsonPath("$.backendMode").value("fake"))
                .andExpect(jsonPath("$.result.postCount", greaterThanOrEqualTo(1)));
        mockMvc.perform(get(REST).param("mode", "explicit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("HIT"))
                .andExpect(jsonPath("$.key").value("stats:rest"));
    }

    @Test
    void annotationPathUsesItsOwnKeyNamespaceSoModesNeverCollide() throws Exception {
        mockMvc.perform(get(REST).param("mode", "explicit"))
                .andExpect(jsonPath("$.cache").value("MISS"));
        // The annotation path must MISS on the same topic: its key lives under
        // topicStats:, never under stats: — the no-collision contract, pinned.
        mockMvc.perform(get(REST).param("mode", "annotation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.key").value("topicStats:rest"))
                .andExpect(jsonPath("$.mode").value("annotation"));
        mockMvc.perform(get(REST).param("mode", "annotation"))
                .andExpect(jsonPath("$.cache").value("HIT"))
                .andExpect(jsonPath("$.key").value("topicStats:rest"));
    }

    @Test
    void defaultModeIsExplicitAndUnknownModesAreValidationEnvelopes() throws Exception {
        mockMvc.perform(get(REST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("explicit"))
                .andExpect(jsonPath("$.key").value("stats:rest"));
        mockMvc.perform(get(REST).param("mode", "magic"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void invalidationNamesBothKeysAndForcesTheNextMiss() throws Exception {
        mockMvc.perform(get(REST).param("mode", "explicit"))
                .andExpect(jsonPath("$.cache").value("MISS"));
        mockMvc.perform(get(REST).param("mode", "explicit"))
                .andExpect(jsonPath("$.cache").value("HIT"));

        mockMvc.perform(post("/api/demo/caching/invalidate/rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicId").value("rest"))
                .andExpect(jsonPath("$.evictedKeys[0]").value("stats:rest"))
                .andExpect(jsonPath("$.evictedKeys[1]").value("topicStats:rest"));

        mockMvc.perform(get(REST).param("mode", "explicit"))
                .andExpect(jsonPath("$.cache").value("MISS"));
    }

    @Test
    void theEmptyAnswerIsCachedTooNegativeCaching() throws Exception {
        mockMvc.perform(get("/api/demo/caching/stats/no-such-tag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.result.postCount").value(0));
        mockMvc.perform(get("/api/demo/caching/stats/no-such-tag"))
                .andExpect(jsonPath("$.cache").value("HIT"))
                .andExpect(jsonPath("$.result.postCount").value(0));
    }

    @Test
    void httpCacheSpecimenServesValidatorsAndCacheControl() throws Exception {
        mockMvc.perform(get("/api/demo/caching/http-cache/{postId}", SEED_POST))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"v1\""))
                .andExpect(header().string("Cache-Control", "max-age=30"))
                .andExpect(header().exists("Last-Modified"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aGenuineIfNoneMatchRoundTripAnswers304WithNoBody() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/demo/caching/http-cache/{postId}", SEED_POST))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");

        mockMvc.perform(get("/api/demo/caching/http-cache/{postId}", SEED_POST)
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""))
                .andExpect(header().string("ETag", etag));
    }

    @Test
    void unknownPostsRemainTyped404Envelopes() throws Exception {
        mockMvc.perform(get("/api/demo/caching/http-cache/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void headersEndpointAnswersInsteadOfThePathVariableRoute() throws Exception {
        mockMvc.perform(get("/api/demo/caching/http-cache/headers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers.ETag").exists())
                .andExpect(jsonPath("$.headers.Cache-Control").value("max-age=30"))
                .andExpect(jsonPath("$.roundTrip.length()").value(4));
    }

    @Test
    void injectedFailureAnswersTheTypedUnavailableEnvelope() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));
        mockMvc.perform(get(REST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.area").value("caching"));
        mockMvc.perform(get("/api/demo/caching/http-cache/{postId}", SEED_POST))
                .andExpect(status().isServiceUnavailable());
    }
}
