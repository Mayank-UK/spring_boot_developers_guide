package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.middleware.CompletedTrace;
import guide.core.result.Result;
import guide.domain.middleware.PipelineFacts;
import guide.domain.middleware.RegisteredMiddleware;
import guide.domain.middleware.ShowPipelineTrace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 08's demo endpoint: the wire contract the client demo
 * parses (current trace, previous completed trace, registered filters,
 * analysis) and the Result → HTTP mapping through the real advice. The
 * slice runs WITHOUT the servlet filter chain — which is exactly why the
 * analysis layer must tolerate filterless traces.
 */
@WebMvcTest(MiddlewareDemoController.class)
class MiddlewareDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShowPipelineTrace showPipelineTrace;

    @Test
    void pipelineServesTheDemoWireShape() throws Exception {
        // the slice runs no servlet filters, so the controller hands the use case
        // exactly what it captured itself: the handler marker, and requestId "n/a"
        when(showPipelineTrace.execute("n/a", List.of("handler:middleware-pipeline")))
                .thenReturn(Result.success(new PipelineFacts(
                        "req-7",
                        List.of("filter:ordering:before", "filter:trace:before",
                                "interceptor:preHandle", "handler:middleware-pipeline"),
                        new CompletedTrace("req-6", "GET", "/actuator/health", 200,
                                List.of("filter:ordering:before", "filter:trace:before",
                                        "filter:trace:after", "filter:ordering:after")),
                        List.of(
                                new RegisteredMiddleware("characterEncodingFilter",
                                        Integer.MIN_VALUE, "@Bean filter (auto-registered)"),
                                new RegisteredMiddleware("middlewareOrderingFilter", 20,
                                        "@Bean filter (auto-registered)"),
                                new RegisteredMiddleware("pipelineTraceFilterRegistration", 30,
                                        "FilterRegistrationBean"),
                                new RegisteredMiddleware("requestIdFilter", Integer.MAX_VALUE,
                                        "@Bean filter (auto-registered)")),
                        List.of("ordering proven", "wrap proven"))));

        mockMvc.perform(get("/api/demo/middleware/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-7"))
                .andExpect(jsonPath("$.currentRequestTrace.length()").value(4))
                .andExpect(jsonPath("$.currentRequestTrace[0]").value("filter:ordering:before"))
                .andExpect(jsonPath("$.currentRequestTrace[3]").value("handler:middleware-pipeline"))
                .andExpect(jsonPath("$.previousCompletedTrace.requestId").value("req-6"))
                .andExpect(jsonPath("$.previousCompletedTrace.path").value("/actuator/health"))
                .andExpect(jsonPath("$.previousCompletedTrace.status").value(200))
                .andExpect(jsonPath("$.previousCompletedTrace.markers.length()").value(4))
                .andExpect(jsonPath("$.registeredFilters.length()").value(4))
                .andExpect(jsonPath("$.registeredFilters[1].name").value("middlewareOrderingFilter"))
                .andExpect(jsonPath("$.registeredFilters[1].order").value(20))
                .andExpect(jsonPath("$.registeredFilters[2].name").value("pipelineTraceFilterRegistration"))
                .andExpect(jsonPath("$.registeredFilters[2].order").value(30))
                .andExpect(jsonPath("$.registeredFilters[2].registration").value("FilterRegistrationBean"))
                .andExpect(jsonPath("$.analysis.length()").value(2));
    }

    @Test
    void aNullPreviousTraceSerializesAsNullNotEmpty() throws Exception {
        // the controller always marks the handler marker before reading — even in a slice
        when(showPipelineTrace.execute("n/a", List.of("handler:middleware-pipeline")))
                .thenReturn(Result.success(new PipelineFacts(
                        "n/a", List.of(), null, List.of(), List.of("no markers captured"))));

        mockMvc.perform(get("/api/demo/middleware/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousCompletedTrace").doesNotExist())
                .andExpect(jsonPath("$.currentRequestTrace").isEmpty())
                .andExpect(jsonPath("$.analysis[0]").value("no markers captured"));
    }

    @Test
    void aFailedResultLeavesAsTheTypedEnvelope() throws Exception {
        when(showPipelineTrace.execute("n/a", List.of("handler:middleware-pipeline")))
                .thenReturn(Result.failed(Failure.of(
                        FailureKind.INTERNAL, "trace introspection unavailable",
                        java.util.Map.of("demo", "middleware:pipeline"))));

        mockMvc.perform(get("/api/demo/middleware/pipeline"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("trace introspection unavailable"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.demo").value("middleware:pipeline"));
    }
}
