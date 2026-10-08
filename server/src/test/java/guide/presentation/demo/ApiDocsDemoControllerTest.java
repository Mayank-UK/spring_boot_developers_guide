package guide.presentation.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Topic 12's coverage endpoint against the REAL server (RANDOM_PORT, real
 * HTTP — the endpoint self-fetches /v3/api-docs, which needs a live port).
 *
 * Resilient by design, matching the endpoint's contract: counts are asserted
 * as RANGES (siblings keep adding endpoints to the same spec), groups as
 * MEMBERSHIP, and each sample group is capped — the demo reports the surface,
 * it never pretends to enumerate it exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsDemoControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    private int port;

    @Test
    void coverageReportsTheLiveDocument() throws Exception {
        JsonNode report = fetchCoverage();

        assertEquals("3.1.0", report.path("specVersion").asString());
        assertEquals("/docs", report.path("swaggerUiUrl").asString());

        assertTrue(report.path("paths").asInt() >= 20,
                "the whole app's surface is documented, not just the posts demo");
        assertTrue(report.path("operations").asInt() >= report.path("paths").asInt(),
                "every path carries at least one operation");
        assertTrue(report.path("schemas").asInt() >= 5,
                "Post, PagedPosts, and the request bodies at minimum");
    }

    @Test
    void samplePathsGroupTheCuratedSurfaces() throws Exception {
        JsonNode samplePaths = fetchCoverage().path("samplePaths");

        assertTrue(samplePaths.path("posts").isArray());
        assertTrue(samplePaths.path("posts").toString().contains("/api/demo/api/posts"),
                "the posts substrate groups under its own name");
        assertTrue(samplePaths.path("content").isArray());
        assertTrue(samplePaths.path("content").toString().contains("/api/content"));
        assertTrue(samplePaths.path("health").isArray());

        samplePaths.properties().forEach(group -> {
            int size = group.getValue().size();
            assertTrue(size >= 1 && size <= 4,
                    "group '" + group.getKey() + "' must stay a capped sample, got " + size);
        });
    }

    private JsonNode fetchCoverage() throws Exception {
        // 60s, not 10: the endpoint self-fetches /v3/api-docs, and springdoc's
        // on-demand full-spec generation now scans 44 topics' worth of
        // controllers — under full-suite load the first call can outrun a
        // 10s budget (it did, twice, at 44 topics). The contract under test
        // is "the report is derived and correct", not a latency bound.
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/demo/api-docs/coverage"))
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "coverage must answer 200, body: " + response.body());
        return JSON.readTree(response.body());
    }
}
