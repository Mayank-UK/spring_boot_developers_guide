package guide.domain.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.Failure;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TraceRequestFlow unit test with FAKE ports — no HTTP, no Spring, no
 * aspect: the story assembly is a pure function of the evidence the ports
 * hand it, so the fakes dictate exactly what was "measured" and the test
 * asserts the layered story renders it faithfully — markers translated in
 * order, the handler stages inserted into the preHandle/postHandle gap,
 * the aspect record cited, timings derived, and the honest degradation
 * when no completed trace matches the probe's correlation id.
 */
class TraceRequestFlowTest {

    /** Fake probe: returns a canned call, no network. */
    private static final class FakeProbe implements InternalProbe {
        InternalCall next = new InternalCall(200, "inner-1", 4567L, 900_000L);

        @Override
        public InternalCall get(String path) {
            return next;
        }
    }

    /** Fake instrumentation: hands back scripted evidence. */
    private static final class FakeInstrumentation implements FlowInstrumentation {
        /** Records that existed BEFORE the probe — resetInvocations() clears these. */
        final List<FlowInstrumentation.ObservedInvocation> invocations = new ArrayList<>();

        /**
         * Records the real aspect would file DURING the probe (it files when
         * ProbeInternalApi.execute returns — after the reset, before the read).
         */
        final List<FlowInstrumentation.ObservedInvocation> filedAfterProbe = new ArrayList<>();

        FlowInstrumentation.ObservedTrace latest;

        @Override
        public void resetInvocations() {
            invocations.clear();
        }

        @Override
        public List<FlowInstrumentation.ObservedInvocation> recentInvocations(int limit) {
            List<FlowInstrumentation.ObservedInvocation> all = new ArrayList<>(invocations);
            all.addAll(filedAfterProbe);
            return List.copyOf(all);
        }

        @Override
        public ObservedTrace latestCompletedTrace() {
            return latest;
        }
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw new AssertionError(failed.failure().message());
        };
    }

    private static List<String> labels(TraceRequestFlow.RequestFlowStory story) {
        return story.steps().stream().map(TraceRequestFlow.StoryStep::label).toList();
    }

    @Test
    void theStoryAssemblesFromRealEvidenceInLayerOrder() {
        FakeProbe probe = new FakeProbe();
        FakeInstrumentation instrumentation = new FakeInstrumentation();
        instrumentation.latest = new FlowInstrumentation.ObservedTrace("inner-1", "GET", "/api/content/architecture", 200,
                List.of("filter:ordering:before", "filter:trace:before", "interceptor:preHandle",
                        "interceptor:postHandle", "interceptor:afterCompletion",
                        "filter:trace:after", "filter:ordering:after"));

        TraceRequestFlow.RequestFlowStory story = unwrap(new TraceRequestFlow(
                new ProbeInternalApi(probe), instrumentation)
                .execute("content", "outer-1", List.of("filter:ordering:before", "handler:arch-tracer")));
        assertNotNull(story);

        assertEquals("content", story.target());
        assertEquals("/api/content/architecture", story.path());
        assertEquals("ContentController.detail", story.controller());
        assertEquals("outer-1", story.outer().requestId());
        assertEquals("inner-1", story.inner().requestId());
        assertTrue(story.inner().matched(), "the trace matched the probe's correlation id");

        List<String> labels = labels(story);
        assertEquals("outermost filter (order 20) entered — request-id stamped before anything else",
                labels.getFirst());
        int preHandle = labels.indexOf("interceptor preHandle — MVC stage begins, handler not yet invoked");
        int controller = labels.indexOf("ContentController.detail — thin: parse the input, call ONE use case, "
                + "map the Result");
        int postHandle = labels.indexOf("interceptor postHandle — handler returned, response rendering ahead");
        assertTrue(preHandle >= 0 && controller > preHandle && postHandle > controller,
                "the handler stages sit inside the preHandle/postHandle gap");
        assertEquals("response left the server — status 200, 4567 body bytes", labels.getLast());
    }

    @Test
    void theAspectRecordBecomesTheUseCaseStepAndTheTimings() {
        FakeProbe probe = new FakeProbe();
        FakeInstrumentation instrumentation = new FakeInstrumentation();
        instrumentation.filedAfterProbe.add(new FlowInstrumentation.ObservedInvocation(
                "guide.domain.arch.ProbeInternalApi.execute", "(POSTS)", "ok",
                1_000_000L, "outer-9"));

        TraceRequestFlow.RequestFlowStory story = unwrap(new TraceRequestFlow(
                new ProbeInternalApi(probe), instrumentation)
                .execute("posts", "outer-9", List.of()));
        assertEquals("posts", story.target());
        assertEquals("/api/demo/api/posts?page=0&size=3", story.path());

        TraceRequestFlow.StoryStep useCaseStep = story.steps().stream()
                .filter(step -> "use-case".equals(step.stage()))
                .findFirst().orElseThrow();
        assertEquals("guide.domain.arch.ProbeInternalApi.execute → ok in 1000000 ns (requestId outer-9)",
                useCaseStep.label());        assertEquals("topic 06 aspect record", useCaseStep.evidence());

        assertEquals(900_000L, story.timings().roundTripNanos());
        assertEquals(1_000_000L, story.timings().useCaseNanos());
        assertEquals(100_000L, story.timings().overheadNanos());
        assertEquals(1, story.invocations().size());
    }

    @Test
    void anUnmatchedTraceDegradesHonestly() {
        FakeProbe probe = new FakeProbe();
        FakeInstrumentation instrumentation = new FakeInstrumentation();
        instrumentation.latest = new FlowInstrumentation.ObservedTrace("someone-else", "GET", "/api/content", 200,
                List.of("filter:ordering:before"));

        TraceRequestFlow.RequestFlowStory story = unwrap(new TraceRequestFlow(
                new ProbeInternalApi(probe), instrumentation)
                .execute("content", "outer-2", List.of()));
        assertNull(story.inner(), "no matching trace is reported as absent, not guessed");
        assertTrue(story.steps().stream().anyMatch(step -> step.label().contains("no completed pipeline trace")
                        || step.evidence().contains("honestly empty")),
                "the story says why the marker evidence is missing");
    }

    @Test
    void anUnknownTargetIsAValidationFailure() {
        Result<TraceRequestFlow.RequestFlowStory> result =
                new TraceRequestFlow(new ProbeInternalApi(new FakeProbe()), new FakeInstrumentation())
                        .execute("nonsense", "outer-3", List.of());
        Failed<TraceRequestFlow.RequestFlowStory> failed =
                assertInstanceOf(Failed.class, result);
        Failure failure = failed.failure();
        assertEquals(guide.core.errors.FailureKind.VALIDATION, failure.kind());
        assertTrue(failure.message().contains("content, posts"));
    }
}
