package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.data.mongo.InMemoryVisitStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * THE OPTIONAL-SERVICE PROOF, mongo edition: the WHOLE application context
 * boots — starter on the classpath, autoconfigured client built, every
 * MongoDemoConfig bean wired — with MongoDB pointed at a port nothing will
 * ever answer, and the console works FULLY from the seeded fake. The
 * degrade-gracefully rule, restated for a second optional store:
 * nothing Mongo-shaped gates boot, /actuator/health stays green (the
 * indicator is off), and a live flip that cannot connect answers an
 * honest 200-with-hint while every normal endpoint keeps serving fake
 * data. (grpc moves to an ephemeral port: contexts that differ from the
 * suite's cached one must not fight over 9090.)
 */
@SpringBootTest(properties = {
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/guide?serverSelectionTimeoutMS=300&connectTimeoutMS=300",
        "spring.grpc.server.port=0"})
@AutoConfigureMockMvc
class MongoDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryVisitStore fake;

    @BeforeEach
    void resetTheFake() {
        fake.reset();
    }

    @Test
    void theConsoleServesTheSeededFakeWhileMongoIsUnreachable() throws Exception {
        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("mongo"))
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.totalVisits").value(24))
                .andExpect(jsonPath("$.avgDurationMs").value(185_583))
                .andExpect(jsonPath("$.topTopics[0].topicId").value("jdbc-sql"))
                .andExpect(jsonPath("$.topTopics[0].visits").value(10))
                .andExpect(jsonPath("$.localeHistogram[?(@.locale == 'en')].visits")
                        .value(org.hamcrest.Matchers.hasItem(14)));

        mockMvc.perform(get("/api/demo/mongo/visits/recent").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(3))
                .andExpect(jsonPath("$.visits[0].id").value("v24"))
                .andExpect(jsonPath("$.visits[0].visitorHash").isNotEmpty());
    }

    @Test
    void recordingAnswersTheStoredDocumentWithTheHashedVisitor() throws Exception {
        mockMvc.perform(postJson("/api/demo/mongo/visits", """
                {"topicId": "mongodb", "path": "/topics/mongodb",
                 "visitorToken": "reader-ten", "durationMs": 42000, "locale": "ja"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.visit.topicId").value("mongodb"))
                .andExpect(jsonPath("$.visit.visitorHash")
                        .value(guide.domain.mongo.VisitDocument.sha256Hex("reader-ten")))
                .andExpect(jsonPath("$.visit.visitorToken").doesNotExist());

        mockMvc.perform(get("/api/demo/mongo/stats").param("topicId", "mongodb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filteredByTopic").value(true))
                .andExpect(jsonPath("$.topicVisits").value(1));
    }

    @Test
    void malformedBodiesAnswerTheTyped422NamingEveryFieldAtOnce() throws Exception {
        mockMvc.perform(postJson("/api/demo/mongo/visits", """
                {"topicId": "Not_A_Slug", "path": "no-slash", "durationMs": -1, "locale": "english"}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.fields.topicId").exists())
                .andExpect(jsonPath("$.error.fields.path").exists())
                .andExpect(jsonPath("$.error.fields.durationMs").exists())
                .andExpect(jsonPath("$.error.fields.locale").exists());

        mockMvc.perform(get("/api/demo/mongo/visits/recent").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void theExplainArtifactServesThePipelinesInFakeModeToo() throws Exception {
        mockMvc.perform(get("/api/demo/mongo/explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.pipelines.length()").value(3))
                .andExpect(jsonPath("$.pipelines[0].name").value("topTopics"))
                .andExpect(jsonPath("$.pipelines[0].stages[0].$group").exists());
    }

    @Test
    void aLiveFlipThatCannotConnectStaysFakeAndAnswersAnHonestHint() throws Exception {
        mockMvc.perform(postJson("/api/demo/mongo/mode", """
                {"dataMode": "live"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.requested").value("live"))
                .andExpect(jsonPath("$.hint").isNotEmpty());

        // The failed flip leaves no half-state: the console still serves fake data.
        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.totalVisits").value(24));
    }

    @Test
    void failureInjectionGuardsTheConsoleLikeEveryDemo() throws Exception {
        mockMvc.perform(patchScenario("{\"injectFailure\": true}"));
        try {
            mockMvc.perform(get("/api/demo/mongo/stats"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("service_unavailable"));
        } finally {
            mockMvc.perform(patchScenario("{\"injectFailure\": false}"));
        }
        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk());
    }

    @Test
    void bogusModeValuesAnswerTheTypedValidation() throws Exception {
        mockMvc.perform(postJson("/api/demo/mongo/mode", "{\"dataMode\": \"cloud\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder patchScenario(String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/demo/scenario")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
