package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import guide.config.PerfDemoConfig;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the perf:bench demo with the topic's own wiring. The slice
 * loads application.yml, so the topic-38 teaching blocks are LIVE here:
 * the report's serverCompression must read the effective posture (enabled,
 * the mime list, the 1KB floor) rather than any hardcoded claim. Pinned
 * per the benchmark's honesty rules: byte counts and ratios are asserted
 * (they are deterministic — same bytes in, same bytes out), timing is
 * asserted only as a non-negative median (micros move run to run, and that
 * is the lesson, not a bug).
 */
@WebMvcTest(PerfDemoController.class)
@Import(PerfDemoConfig.class)
class PerfDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theDefaultRunMeasuresMediumAtBothLevels() throws Exception {
        mockMvc.perform(get("/api/demo/perf/bench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadProfile").value("medium"))
                .andExpect(jsonPath("$.payloadBytes").value(Matchers.greaterThanOrEqualTo(4096)))
                .andExpect(jsonPath("$.levels.length()").value(2))
                .andExpect(jsonPath("$.levels[0].level").value(1))
                .andExpect(jsonPath("$.levels[1].level").value(6))
                .andExpect(jsonPath("$.payloadItems").value(Matchers.greaterThan(5)));
    }

    @Test
    void gzipShrinksThePayloadWellBelowIdentityAndMoreAtHigherLevel() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/perf/bench"))
                .andExpect(status().isOk())
                .andReturn();
        // numbers arrive as Double/BigDecimal depending on the JSON provider —
        // read them as Numbers and compare in Java, never in matcher generics
        String body = result.getResponse().getContentAsString();
        int payloadBytes = ((Number) JsonPath.read(body, "$.payloadBytes")).intValue();
        int levelOneBytes = ((Number) JsonPath.read(body, "$.levels[0].compressedBytes")).intValue();
        int levelSixBytes = ((Number) JsonPath.read(body, "$.levels[1].compressedBytes")).intValue();
        int levelOneRaw = ((Number) JsonPath.read(body, "$.levels[0].rawBytes")).intValue();
        double ratioOne = ((Number) JsonPath.read(body, "$.levels[0].compressedToRawRatio")).doubleValue();
        double ratioSix = ((Number) JsonPath.read(body, "$.levels[1].compressedToRawRatio")).doubleValue();
        org.junit.jupiter.api.Assertions.assertEquals(payloadBytes, levelOneRaw,
                "identity bytes are the floor every level is measured against");
        org.junit.jupiter.api.Assertions.assertTrue(ratioOne > 0.05 && ratioOne < 0.6,
                "gzip level 1 must shrink the real-shaped payload to a realistic ratio (got " + ratioOne + ")");
        org.junit.jupiter.api.Assertions.assertTrue(ratioSix > 0.05 && ratioSix < 0.6,
                "gzip level 6 must shrink the real-shaped payload to a realistic ratio (got " + ratioSix + ")");
        org.junit.jupiter.api.Assertions.assertTrue(levelOneBytes < payloadBytes,
                "gzip level 1 must shrink the real-shaped payload");
        org.junit.jupiter.api.Assertions.assertTrue(levelSixBytes <= levelOneBytes,
                "level 6 never loses to level 1 on the same deterministic bytes");
    }

    @Test
    void mediansAreReportedButNeverPinnedToExactTime() throws Exception {
        mockMvc.perform(get("/api/demo/perf/bench?passes=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.levels[0].medianCompressMicros")
                        .value(Matchers.greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.levels[0].medianRoundTripMicros")
                        .value(Matchers.greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.reading[3]")
                        .value(Matchers.containsString("5 timed passes")))
                .andExpect(jsonPath("$.reading[3]")
                        .value(Matchers.containsString("warm-ups")));
    }

    @Test
    void theEffectiveConnectorPostureComesFromTheLiveConfig() throws Exception {
        mockMvc.perform(get("/api/demo/perf/bench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverCompression.enabled").value(true))
                .andExpect(jsonPath("$.serverCompression.mimeTypes[0]").value("application/json"))
                .andExpect(jsonPath("$.serverCompression.minResponseSize").value("1KB"))
                .andExpect(jsonPath("$.reading[4]")
                        .value(Matchers.containsString("ON")))
                .andExpect(jsonPath("$.reading[4]")
                        .value(Matchers.containsString("Accept-Encoding")));
    }

    @Test
    void thePayloadProfilesChangeTheIdentityFloor() throws Exception {
        mockMvc.perform(get("/api/demo/perf/bench?payload=small"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadProfile").value("small"))
                .andExpect(jsonPath("$.payloadBytes").value(Matchers.greaterThanOrEqualTo(1024)))
                .andExpect(jsonPath("$.payloadBytes").value(Matchers.lessThan(5000)));
        mockMvc.perform(get("/api/demo/perf/bench?payload=large&passes=8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadProfile").value("large"))
                .andExpect(jsonPath("$.payloadBytes").value(Matchers.greaterThanOrEqualTo(16384)))
                .andExpect(jsonPath("$.payloadItems").value(Matchers.greaterThan(40)));
    }

    @Test
    void badInputIsTheTypedValidationEnvelopeNeverA500() throws Exception {
        mockMvc.perform(get("/api/demo/perf/bench?payload=huge"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(Matchers.containsString("payload must be one of")));
        mockMvc.perform(get("/api/demo/perf/bench?passes=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(Matchers.containsString("passes must be an integer")));
        mockMvc.perform(get("/api/demo/perf/bench?passes=not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }
}
