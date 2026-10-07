package guide.presentation.demo;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.Ordered;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Topic 08's demo against the REAL application context — @SpringBootTest +
 * auto-configured MockMvc, which (unlike a bare @WebMvcTest slice) applies
 * the registered servlet filter chain to every request. This is where the
 * topic's central claim becomes executable: a request's marker sequence is
 * exactly the middleware pipeline, filter before interceptor before handler,
 * with the unwind landing in the ring only after the response is written.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiddlewarePipelineIntegrationTest {

    private static final String PIPELINE = "/api/demo/middleware/pipeline";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theCurrentTraceProvesFilterBeforeInterceptorBeforeHandler() throws Exception {
        mockMvc.perform(get(PIPELINE))
                .andExpect(status().isOk())
                // exactly four markers — nothing else marks /api requests
                .andExpect(jsonPath("$.currentRequestTrace.length()").value(4))
                .andExpect(jsonPath("$.currentRequestTrace[0]").value("filter:ordering:before"))
                .andExpect(jsonPath("$.currentRequestTrace[1]").value("filter:trace:before"))
                .andExpect(jsonPath("$.currentRequestTrace[2]").value("interceptor:preHandle"))
                .andExpect(jsonPath("$.currentRequestTrace[3]").value("handler:middleware-pipeline"))
                // the unwind cannot be inside the very response it trails: with exactly
                // four markers — all named above — no postHandle/afterCompletion can hide
                .andExpect(jsonPath("$.analysis.length()", greaterThanOrEqualTo(3)));
    }

    @Test
    void theRingHoldsThePreviousRequestWithTheFullUnwind() throws Exception {
        // warm the ring, then ask again: the SECOND response's "previous" is the FIRST request,
        // with the complete eight-marker unwind including both filter after-markers
        mockMvc.perform(get(PIPELINE)).andExpect(status().isOk());

        mockMvc.perform(get(PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousCompletedTrace").isNotEmpty())
                .andExpect(jsonPath("$.previousCompletedTrace.path").value(PIPELINE))
                .andExpect(jsonPath("$.previousCompletedTrace.status").value(200))
                .andExpect(jsonPath("$.previousCompletedTrace.requestId").isNotEmpty())
                .andExpect(jsonPath("$.previousCompletedTrace.markers.length()").value(8))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[0]").value("filter:ordering:before"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[1]").value("filter:trace:before"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[2]").value("interceptor:preHandle"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[3]").value("handler:middleware-pipeline"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[4]").value("interceptor:postHandle"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[5]").value("interceptor:afterCompletion"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[6]").value("filter:trace:after"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[7]").value("filter:ordering:after"))
                // the second request's OWN trace still stops at the handler
                .andExpect(jsonPath("$.currentRequestTrace.length()").value(4))
                .andExpect(jsonPath("$.currentRequestTrace[3]").value("handler:middleware-pipeline"));
    }

    @Test
    void everyRequestFlowsTheChainEvenActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get(PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousCompletedTrace.path").value("/actuator/health"))
                // filters saw the health request; the interceptor did NOT (pattern is /api/**)
                .andExpect(jsonPath("$.previousCompletedTrace.markers.length()").value(4))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[0]").value("filter:ordering:before"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[1]").value("filter:trace:before"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[2]").value("filter:trace:after"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[3]").value("filter:ordering:after"));
    }

    @Test
    void theFilterIntrospectionReportsTheRealRegistrations() throws Exception {
        mockMvc.perform(get(PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredFilters[?(@.name=='middlewareOrderingFilter')].order",
                        contains(20)))
                .andExpect(jsonPath(
                        "$.registeredFilters[?(@.name=='middlewareOrderingFilter')].registration",
                        contains("@Bean filter (auto-registered)")))
                .andExpect(jsonPath(
                        "$.registeredFilters[?(@.name=='pipelineTraceFilterRegistration')].order",
                        contains(30)))
                .andExpect(jsonPath(
                        "$.registeredFilters[?(@.name=='pipelineTraceFilterRegistration')].registration",
                        contains("FilterRegistrationBean")))
                .andExpect(jsonPath("$.registeredFilters[?(@.name=='requestIdFilter')].order",
                        contains(Ordered.LOWEST_PRECEDENCE)))
                .andExpect(jsonPath("$.registeredFilters.length()").value(greaterThanOrEqualTo(3)));
    }

    @Test
    void anIncomingRequestIdTravelsTheWholeChainAndBack() throws Exception {
        mockMvc.perform(get(PIPELINE).header("x-request-id", "custom-marker-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("custom-marker-42"))
                .andExpect(header().string("x-request-id", "custom-marker-42"));
    }

    @Test
    void evenAnUnmappedPathRidesTheChainAndTheRingRemembersIt() throws Exception {
        // no handler exists for this path; Boot's /** static-resource fallback mapping
        // still matches, so the interceptor's preHandle runs — then the handler throws
        // (NoResourceFoundException → the advice's ErrorResponse branch renders it with
        // the framework's own status: the typed 404 envelope, since topic 30),
        // postHandle is SKIPPED on exception, and afterCompletion still fires
        mockMvc.perform(get("/api/definitely-not-mapped")).andExpect(status().isNotFound());

        mockMvc.perform(get(PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousCompletedTrace.path").value("/api/definitely-not-mapped"))
                .andExpect(jsonPath("$.previousCompletedTrace.status").value(404))
                .andExpect(jsonPath("$.previousCompletedTrace.markers.length()").value(6))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[0]").value("filter:ordering:before"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[1]").value("filter:trace:before"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[2]").value("interceptor:preHandle"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[3]").value("interceptor:afterCompletion"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[4]").value("filter:trace:after"))
                .andExpect(jsonPath("$.previousCompletedTrace.markers[5]").value("filter:ordering:after"));
    }
}
