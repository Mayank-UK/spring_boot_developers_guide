package guide.presentation.demo.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The headers lab on a REAL socket (Boot 4 note: TestRestTemplate's package
 * moved in the modularized line, so this test speaks plain JDK HttpClient —
 * the socket is the point, not the client): this context boots with a
 * random HTTP port (grpc moved to 19092 so it cannot collide with the
 * cached mock contexts' 9090), and the assertions read the ACTUAL response
 * headers the two chains write — the main chain carries the full helmet,
 * the insecure-comparison chain carries none of it, and HSTS is honestly
 * absent over plain HTTP (the writer waits for a secure connection). The
 * /headers demo endpoint itself is exercised too: it self-fetches both
 * probes over the loopback socket, so its output being correct HERE proves
 * it reports measured headers, never a hardcoded table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.grpc.server.port=19092",
        "spring.grpc.client.channels.guide.address=static://localhost:19092"})
class SecurityHeadersProbeTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();

    private Map<String, List<String>> get(String path) throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return response.headers().map();
    }

    private static String first(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMainChainWritesTheHelmetAndTheLabReportsItMeasured() throws Exception {
        var secureHeaders = get("/api/demo/security/lab/probe");
        assertEquals("nosniff", first(secureHeaders, "X-Content-Type-Options"));
        assertEquals("DENY", first(secureHeaders, "X-Frame-Options"));
        assertNotNull(first(secureHeaders, "Content-Security-Policy"));
        assertEquals("strict-origin-when-cross-origin", first(secureHeaders, "Referrer-Policy"));
        // honest by design: HSTS is only emitted on secure connections — this is HTTP
        assertNull(first(secureHeaders, "Strict-Transport-Security"));

        Map<String, Object> lab = labPayload("/api/demo/security/lab/headers");
        List<Map<String, Object>> secure = (List<Map<String, Object>>) lab.get("secure");
        Map<String, Map<String, Object>> byHeader = new HashMap<>();
        for (Map<String, Object> measured : secure) {
            byHeader.put((String) measured.get("header"), measured);
        }
        assertEquals("nosniff", byHeader.get("X-Content-Type-Options").get("value"));
        assertEquals("DENY", byHeader.get("X-Frame-Options").get("value"));
        assertTrue(((String) byHeader.get("Content-Security-Policy").get("value"))
                .contains("default-src 'self'"));
        assertNull(byHeader.get("Strict-Transport-Security").get("value"),
                "the lab reports HSTS's absence over HTTP honestly");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theInsecureComparisonChainWritesNoneOfThem() throws Exception {
        var headers = get("/api/demo/security/lab/insecure/probe");
        assertNull(first(headers, "X-Content-Type-Options"));
        assertNull(first(headers, "X-Frame-Options"));
        assertNull(first(headers, "Content-Security-Policy"));
        assertNull(first(headers, "Referrer-Policy"));

        Map<String, Object> lab = labPayload("/api/demo/security/lab/headers");
        List<Map<String, Object>> insecure =
                (List<Map<String, Object>>) lab.get("insecureComparison");
        for (Map<String, Object> measured : insecure) {
            assertNull(measured.get("value"),
                    measured.get("header") + " must be absent on the relaxed chain");
            assertTrue(((String) measured.get("note")).contains("ABSENT"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCorsLabMeasuresBothLivePreflightsOverTheSocket() throws Exception {
        Map<String, Object> lab = labPayload("/api/demo/security/lab/cors/preflight");
        Map<String, Object> allowlisted = (Map<String, Object>) lab.get("allowlistedOrigin");
        assertEquals(200, ((Number) allowlisted.get("status")).intValue());
        assertEquals("allowed — the origin is on the allowlist", allowlisted.get("verdict"));

        Map<String, Object> unlisted = (Map<String, Object>) lab.get("unlistedOrigin");
        assertEquals(403, ((Number) unlisted.get("status")).intValue());
        assertTrue(((String) unlisted.get("verdict")).contains("rejected"));
    }

    @Test
    void bothProbeRoutesAnswerOnThisContextsSocket() throws Exception {
        assertEquals(200, statusOf("/api/demo/security/lab/probe"));
        assertEquals(200, statusOf("/api/demo/security/lab/insecure/probe"));
        assertTrue(context.containsBean("securityFilterChain"), "the main chain is live");
    }

    private int statusOf(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> labPayload(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        // Jackson 3 (Boot 4's databind lives in tools.jackson.*) — readValue
        // throws unchecked in 3.x
        return (Map<String, Object>) tools.jackson.databind.json.JsonMapper.builder()
                .build().readValue(response.body(), Map.class);
    }
}
