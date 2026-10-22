package guide.presentation.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The traced flow against the REAL server (RANDOM_PORT, real HTTP): the
 * probe's self-call crosses a genuine network hop with a hand-built W3C
 * traceparent, and THIS test asserts the whole thesis — one trace id,
 * present in the response, in every finished span of the flow, and in the
 * log lines the hop's thread wrote. Plus the always-on machinery: the
 * request-logging filter's completion line lands in the ring with BOTH MDC
 * ids, the actuator probes answer, and /actuator/prometheus exposes this
 * app's meters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObsTracingIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "body was: " + response.body());
        return JSON.readTree(response.body());
    }

    @Test
    void theTracedFlowCarriesOneIdAcrossEveryHop() throws Exception {
        JsonNode evidence = getJson("/api/demo/obs/trace");

        String traceId = evidence.path("traceId").asString();
        assertFalse(traceId.isBlank(), "the flow reports a trace id");
        assertTrue(evidence.path("traceparentSent").asString().startsWith("00-" + traceId + "-"),
                "the traceparentSent header is built from that same trace id");

        // the hop JOINED the incoming trace — the strongest server-side assertion
        assertEquals(traceId, evidence.path("hop").path("traceIdFromMdc").asString(),
                "the hop's thread carried the caller's trace id in MDC");
        assertTrue(evidence.path("hop").path("traceparentSeen").asString()
                        .matches("00-" + traceId + "-[0-9a-f]{16}-01"),
                "the hop received a well-formed traceparent for that trace");

        // cross-layer work genuinely ran
        assertFalse(evidence.path("hop").path("facts").path("postId").asString().isBlank(),
                "a real post was created through the posts use case");
        assertEquals("succeeded", evidence.path("hop").path("facts").path("outcome").asString(),
                "a real job was drained through topic 29's worker");

        // one id in every finished span of the flow
        JsonNode spans = evidence.path("spans");
        assertTrue(spans.isArray() && spans.size() >= 1, "at least the hop's span finished");
        for (JsonNode span : spans) {
            assertEquals(traceId, span.path("traceId").asString(), "span carries the trace id");
            assertTrue(span.path("durationMs").asDouble() >= 0, "spans carry timings");
        }

        // …and the SAME id in the log lines the hop's thread wrote
        JsonNode logs = evidence.path("logs");
        if (!logs.isArray() || logs.size() < 1) {
            // Self-diagnosing failure: dump the ring's tail so the CI log names the
            // mechanism — MDC decoration dead (every traceId null), window flooded,
            // or ring empty. Local runs never reproduce the CI-only flake.
            JsonNode tail = getJson("/api/demo/obs/logs?limit=30").path("events");
            StringBuilder ringTail = new StringBuilder("the hop's beat is in the ring — ring tail (newest first): ");
            for (JsonNode event : tail) {
                ringTail.append('[').append(event.path("level").asString()).append(' ')
                        .append(event.path("logger").asString())
                        .append(" traceId=").append(event.path("traceId").asString())
                        .append(" req=").append(event.path("requestId").asString()).append("] ");
            }
            assertTrue(false, ringTail.toString());
        }
        for (JsonNode log : logs) {
            assertEquals(traceId, log.path("traceId").asString(), "log line joined the trace");
        }
        assertTrue(logs.toString().contains("outcome succeeded"),
                "the hop's business beat is among them");
    }

    @Test
    void theCompletionLineLandsInTheRingWithBothIds() throws Exception {
        // any request produces a completion line; emit is deterministic and quick.
        // The line is written AFTER the response body is flushed, so the ring can
        // trail the response this test just read — poll briefly instead of racing.
        getJson("/api/demo/obs/logs/emit?level=info");
        boolean sawCompletionLine = false;
        JsonNode events = null;
        for (int attempt = 0; attempt < 10 && !sawCompletionLine; attempt++) {
            JsonNode ring = getJson("/api/demo/obs/logs?limit=30");
            events = ring.path("events");
            for (JsonNode event : events) {
                if (event.path("logger").asString().equals("guide.http")
                        && !event.path("requestId").asString().isBlank()) {
                    sawCompletionLine = true;
                    break;
                }
            }
            if (!sawCompletionLine) {
                Thread.sleep(200);
            }
        }
        assertTrue(events != null && events.isArray() && events.size() > 0, "the ring serves events");
        assertTrue(sawCompletionLine,
                "the always-on filter logged a completion line with a request id into the ring");
    }

    @Test
    void theActuatorServesProbesAndOurMeters() throws Exception {
        JsonNode liveness = getJson("/actuator/health/liveness");
        assertEquals("UP", liveness.path("status").asString());
        JsonNode readiness = getJson("/actuator/health/readiness");
        assertEquals("UP", readiness.path("status").asString());

        HttpResponse<String> prometheus = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, prometheus.statusCode());
        assertTrue(prometheus.body().contains("guide_jobs_outcomes_total"),
                "the outcome counters reach the exposition");
        // prometheus RESERVES the _created suffix (its own creation-timestamp
        // series), so "guide.posts.created" exposes as guide_posts_total —
        // pinned here because it surprises everyone exactly once
        assertTrue(prometheus.body().contains("guide_posts_total"),
                "the posts counter reaches the exposition (prometheus drops the _created suffix)");
        assertTrue(prometheus.body().contains("guide_demo_requests"),
                "the request timer reaches the exposition (the filter feeds it)");

        JsonNode summary = getJson("/api/demo/obs/metrics/summary");
        assertTrue(summary.path("prometheusExcerpt").size() > 0,
                "the summary scrapes the same exposition the endpoint serves");
    }
}
