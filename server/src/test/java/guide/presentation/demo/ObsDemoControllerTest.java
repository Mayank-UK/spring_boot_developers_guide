package guide.presentation.demo;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ObservabilityDemoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the observability demo with the topic's own wiring (plus the
 * slice's SimpleMeterRegistry fallback from the config). The slice has NO
 * tracer and NO live web server, so the traced flow answers its honest
 * typed 503 here — the full proof lives in ObsTracingIntegrationTest
 * against a real port. What IS pinned here: the ring serves structured
 * fields, the emitter lands every level (per-logger TRACE overriding the
 * root INFO included), unknown levels are typed validation failures, the
 * metrics summary carries the app's meters with the pre-initialized tag
 * families, and the curl-able hop runs its cross-layer steps as SKIPPED
 * (absent machinery reported, never faked).
 */
@WebMvcTest(ObsDemoController.class)
@Import(ObservabilityDemoConfig.class)
class ObsDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void emitLandsEveryLevelInTheRingWithFields() throws Exception {
        for (String level : new String[] {"trace", "debug", "info", "warn", "error"}) {
            mockMvc.perform(get("/api/demo/obs/logs/emit").param("level", level))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.level").value(level.toUpperCase()))
                    .andExpect(jsonPath("$.logger").value("guide.demo.obs.emitter"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        // … and they are all readable back, filtered by exact level
        mockMvc.perform(get("/api/demo/obs/logs").param("level", "WARN").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(500))
                .andExpect(jsonPath("$.events[0].level").value("WARN"))
                .andExpect(jsonPath("$.events[0].logger").value("guide.demo.obs.emitter"));
    }

    @Test
    void anUnknownLevelIsItselfATypedValidationFailure() throws Exception {
        mockMvc.perform(get("/api/demo/obs/logs/emit").param("level", "loud"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.field").value("level"));
    }

    @Test
    void theRingFiltersByExactLevelAndCapsTheLimit() throws Exception {
        mockMvc.perform(get("/api/demo/obs/logs/emit").param("level", "info"));
        mockMvc.perform(get("/api/demo/obs/logs/emit").param("level", "error"));
        // the ring is a shared singleton across this class's requests, so the
        // assertion is on the FILTER's contract, not on exact counts: every
        // event in an INFO-filtered answer is INFO, and at least one exists
        mockMvc.perform(get("/api/demo/obs/logs").param("level", "INFO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.level != 'INFO')]").isEmpty())
                .andExpect(jsonPath("$.events.length()").value(greaterThanOrEqualTo(1)));
        // a hostile limit is clamped, not obeyed (1..200)
        mockMvc.perform(get("/api/demo/obs/logs").param("limit", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(lessThanOrEqualTo(200)));
    }

    @Test
    void metricsSummaryCarriesTheAppMetersWithPreinitializedTagFamilies() throws Exception {
        mockMvc.perform(get("/api/demo/obs/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meters").isArray())
                .andExpect(jsonPath("$.meters[?(@.name=='guide.posts.created')]").isNotEmpty())
                .andExpect(jsonPath("$.meters[?(@.name=='guide.jobs.outcomes' && @.tags.outcome=='dead')]").isNotEmpty())
                .andExpect(jsonPath("$.meters[?(@.name=='guide.demo.requests' && @.tags.outcome=='success')]").isNotEmpty())
                .andExpect(jsonPath("$.meters[?(@.name=='guide.demo.requests' && @.tags.outcome=='server_error')]").isNotEmpty())
                .andExpect(jsonPath("$.meters[?(@.name=='guide.obs.emitted' && @.tags.level=='trace')]").isNotEmpty())
                .andExpect(jsonPath("$.meters[?(@.name=='guide.obs.ring.size')]").isNotEmpty())
                .andExpect(jsonPath("$.prometheusExcerpt").isArray());
    }

    @Test
    void theTracedFlowAnswersItsHonestUnavailableInASlice() throws Exception {
        mockMvc.perform(get("/api/demo/obs/trace"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @Test
    void theHopIsCurlableAndReportsSkippedStepsWithoutMachinery() throws Exception {
        mockMvc.perform(get("/api/demo/obs/trace/hop").param("origin", "slice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.origin").value("slice"))
                .andExpect(jsonPath("$.facts.postId").doesNotExist())
                .andExpect(jsonPath("$.facts.jobId").doesNotExist())
                .andExpect(jsonPath("$.facts.steps.length()").value(2))
                .andExpect(jsonPath("$.facts.steps[0]").value(
                        "posts substrate absent in this context — post creation skipped"))
                .andExpect(jsonPath("$.facts.steps[1]").value(
                        "jobs machinery absent in this context — job episode skipped"));
    }
}
