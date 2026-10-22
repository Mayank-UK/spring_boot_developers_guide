package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The LIVE end-to-end: the full context with a real MongoDB container
 * wired through @ServiceConnection — the exact path production runs
 * (container for host, the autoconfigured MongoClient + MongoTemplate
 * pointed by Boot, FLAGGED-verified for the 2.x container class:
 * spring-boot-mongodb ships the ConnectionDetailsFactory for
 * org.testcontainers.mongodb.MongoDBContainer).
 *
 * The tour is the optional-service discipline in motion: the console
 * starts FAKE (seeded, instant), the /mode flip activates LAZILY (ping +
 * indexes on first live use — the very call that answers), records then
 * land in the real collection (ObjectId ids — longer than the fake's),
 * the aggregates come back from the $group pipelines, and the flip back
 * to fake serves the seed again with zero restart. grpc sits on an
 * ephemeral port: this context cannot share the suite's cached one.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class MongoDemoIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8")
            .withCreateContainerCmdModifier(cmd -> cmd.withMemory(768 * 1024 * 1024L)
                    .withMemorySwap(768 * 1024 * 1024L))
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theConsoleFlipsLiveLazilyServesRealMongoAndBack() throws Exception {
        // Fake first: the seeded console, MongoDB untouched.
        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.totalVisits").value(24));

        // THE FLIP: this very call performs the lazy activation (ping +
        // indexes) and reports the live database the container gave us.
        mockMvc.perform(post("/api/demo/mongo/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\": \"live\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("live"))
                .andExpect(jsonPath("$.database").isNotEmpty());

        // Live reads start from an empty collection — honest zeros, not the
        // seed: the two stores never pretended to share data.
        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("live"))
                .andExpect(jsonPath("$.totalVisits").value(0));

        // A live record lands in the real collection and aggregates back.
        mockMvc.perform(post("/api/demo/mongo/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topicId": "mongodb", "path": "/topics/mongodb",
                                 "visitorToken": "reader-live", "durationMs": 250000, "locale": "en"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("live"))
                .andExpect(jsonPath("$.visit.id").isNotEmpty());

        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVisits").value(1))
                .andExpect(jsonPath("$.avgDurationMs").value(250_000))
                .andExpect(jsonPath("$.topTopics[0].topicId").value("mongodb"));

        // And back to fake: the seeded console again, no restart anywhere.
        mockMvc.perform(post("/api/demo/mongo/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataMode\": \"fake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"));

        mockMvc.perform(get("/api/demo/mongo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("fake"))
                .andExpect(jsonPath("$.totalVisits").value(24));
    }
}
