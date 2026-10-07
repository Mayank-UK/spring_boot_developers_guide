package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.arch.ArchExemptions;
import guide.domain.arch.BuildLayerMap;
import guide.domain.arch.CodebaseMapSource;
import guide.domain.arch.FlowInstrumentation;
import guide.domain.arch.TraceRequestFlow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 32's demo endpoints: the trace wire shape the client
 * demo parses (target, outer/inner requests, the layered steps with their
 * evidence citations, invocations, timings) and the map wire shape (layers
 * with class counts, the port/adapter inventory with composition-root
 * bindings, both exemption ledgers) — plus the Result → HTTP mapping
 * through the real advice: an unknown target leaves as the 400 validation
 * envelope, not a raw 500.
 */
@WebMvcTest(ArchDemoController.class)
class ArchDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TraceRequestFlow traceRequestFlow;

    @MockitoBean
    private BuildLayerMap buildLayerMap;

    private static TraceRequestFlow.RequestFlowStory story() {
        return new TraceRequestFlow.RequestFlowStory(
                "content", "/api/content/architecture",
                "ContentController.detail", "GetTopicById", "ContentRepositoryAdapter",
                "the guide serving this very page",
                new TraceRequestFlow.OuterRequest("outer-1",
                        List.of("filter:ordering:before", "handler:arch-tracer")),
                new TraceRequestFlow.InnerRequest("inner-1", 200, true,
                        List.of("filter:ordering:before", "interceptor:preHandle",
                                "interceptor:postHandle", "filter:ordering:after")),
                List.of(new FlowInstrumentation.ObservedInvocation(
                        "guide.domain.arch.ProbeInternalApi.execute", "(CONTENT)", "ok",
                        1_234_567L, "outer-1")),
                List.of(
                        new TraceRequestFlow.StoryStep("filter", "outermost filter (order 20) entered",
                                "topic 08 ring: filter:ordering:before"),
                        new TraceRequestFlow.StoryStep("controller",
                                "ContentController.detail — thin: parse the input, call ONE use case, "
                                        + "map the Result",
                                "controller source"),
                        new TraceRequestFlow.StoryStep("use-case",
                                "guide.domain.arch.ProbeInternalApi.execute → ok in 1234567 ns",
                                "topic 06 aspect record"),
                        new TraceRequestFlow.StoryStep("done", "response left the server — status 200",
                                "probe measurement")),
                new TraceRequestFlow.Timings(1_100_000L, 1_234_567L, 134_567L, 200, 4567L));
    }

    @Test
    void traceServesTheLayeredStoryWireShape() throws Exception {
        // slices run no filters/interceptors: the controller sees "n/a" and
        // only its own handler marker — exactly what it captured itself
        when(traceRequestFlow.execute("content", "n/a", List.of("handler:arch-tracer")))
                .thenReturn(Result.success(story()));

        mockMvc.perform(get("/api/demo/arch/trace").queryParam("target", "content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("content"))
                .andExpect(jsonPath("$.path").value("/api/content/architecture"))
                .andExpect(jsonPath("$.controller").value("ContentController.detail"))
                .andExpect(jsonPath("$.outer.requestId").value("outer-1"))
                .andExpect(jsonPath("$.inner.requestId").value("inner-1"))
                .andExpect(jsonPath("$.inner.status").value(200))
                .andExpect(jsonPath("$.inner.matched").value(true))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[1].stage").value("controller"))
                .andExpect(jsonPath("$.steps[2].evidence").value("topic 06 aspect record"))
                .andExpect(jsonPath("$.invocations[0].method")
                        .value("guide.domain.arch.ProbeInternalApi.execute"))
                .andExpect(jsonPath("$.invocations[0].durationNanos").value(1234567))
                .andExpect(jsonPath("$.timings.roundTripNanos").value(1100000))
                .andExpect(jsonPath("$.timings.overheadNanos").value(134567));
    }

    @Test
    void anUnknownTargetLeavesAsTheValidationEnvelope() throws Exception {
        when(traceRequestFlow.execute("nonsense", "n/a", List.of("handler:arch-tracer")))
                .thenReturn(Result.failed(Failure.of(FailureKind.VALIDATION,
                        "target must be one of: content, posts")));

        mockMvc.perform(get("/api/demo/arch/trace").queryParam("target", "nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("target must be one of: content, posts"));
    }

    @Test
    void mapServesLayersPortsAndBothLedgers() throws Exception {
        when(buildLayerMap.execute()).thenReturn(Result.success(new BuildLayerMap.LayerMap(
                List.of(new CodebaseMapSource.LayerCount("domain", "guide.domain", 260,
                        List.of(new CodebaseMapSource.AreaCount("posts", 12),
                                new CodebaseMapSource.AreaCount("arch", 8)))),
                List.of(new CodebaseMapSource.PortBinding("guide.domain.posts.PostRepository",
                        List.of("guide.data.posts.InMemoryPostRepository",
                                "guide.data.posts.PgPostRepository",
                                "guide.data.posts.ScenarioRoutingPostRepository"),
                        List.of("PostsDemoConfig.postRepository"))),
                List.of(new ArchExemptions.TeachingExhibit(
                        "guide/presentation/graphql/PostsGraphqlController.java", "guide.data.comments.",
                        "reads the fake repository's batchCallCount")),
                List.of(new ArchExemptions.DomainExemption(
                        "guide/domain/posts/PostStatus.java", "com.fasterxml.jackson.annotation.",
                        "enum serialized by name")))));

        mockMvc.perform(get("/api/demo/arch/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].layer").value("domain"))
                .andExpect(jsonPath("$.layers[0].classes").value(260))
                .andExpect(jsonPath("$.layers[0].areas[0].area").value("posts"))
                .andExpect(jsonPath("$.ports[0].port").value("guide.domain.posts.PostRepository"))
                .andExpect(jsonPath("$.ports[0].adapters.length()").value(3))
                .andExpect(jsonPath("$.ports[0].bindings[0]").value("PostsDemoConfig.postRepository"))
                .andExpect(jsonPath("$.teachingExhibits[0].file")
                        .value("guide/presentation/graphql/PostsGraphqlController.java"))
                .andExpect(jsonPath("$.domainExemptions[0].importPrefix")
                        .value("com.fasterxml.jackson.annotation."));
    }
}
