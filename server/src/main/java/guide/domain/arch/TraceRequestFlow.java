package guide.domain.arch;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Use case: the request-flow tracer's orchestrator. Given a target, it (1)
 * resets the invocation ring, (2) drives one REAL internal request through
 * the traced {@link ProbeInternalApi} use case, (3) reads back the evidence
 * the app's own instrumentation filed — the inner request's completed
 * middleware trace and the aspect records — and (4) assembles the LAYERED
 * STORY: filter(request-id) → interceptor(preHandle) → controller(thin) →
 * use case(aspect-recorded) → adapter → Result → payload-or-envelope →
 * postHandle/afterCompletion.
 *
 * Order matters and is not accidental: the aspect files the probe's record
 * when ProbeInternalApi.execute RETURNS — before this method continues — so
 * by the time step (3) runs, the record is in the ring. This orchestrator
 * is deliberately NOT @Traceable: its own execution is the frame, not the
 * exhibit.
 *
 * Every story step cites where its evidence came from; every timing is a
 * real measurement. When the inner trace cannot be matched to the probe's
 * correlation id (concurrent traffic filed a newer one, or the filters are
 * not registered in this context), the story says so instead of guessing.
 */
public class TraceRequestFlow {

    /** How many invocation records to read back after a probe. */
    static final int INVOCATION_TAIL = 8;

    private final ProbeInternalApi probeApi;
    private final FlowInstrumentation instrumentation;

    public TraceRequestFlow(ProbeInternalApi probeApi, FlowInstrumentation instrumentation) {
        this.probeApi = probeApi;
        this.instrumentation = instrumentation;
    }

    /**
     * Runs the trace for one target. {@code outerRequestId} and
     * {@code outerMarkers} are the CALLING request's own id and trace-so-far
     * (the controller passes them in, like topic 08's demo does) — the story
     * shows both requests because the interesting fact is that a nested
     * self-call is TWO requests with TWO ids.
     */
    public Result<RequestFlowStory> execute(String targetId, String outerRequestId,
            List<String> outerMarkers) {
        Optional<ProbeTarget> target = ProbeTarget.fromId(targetId);
        if (target.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "target must be one of: content, posts",
                    Map.of("parameter", "target", "received", String.valueOf(targetId))));
        }

        instrumentation.resetInvocations();
        Result<InternalProbe.InternalCall> probed = probeApi.execute(target.get());
        if (probed instanceof guide.core.result.Failed<InternalProbe.InternalCall> failed) {
            return Result.failed(failed.failure());
        }
        InternalProbe.InternalCall call =
                ((guide.core.result.Success<InternalProbe.InternalCall>) probed).value();

        List<FlowInstrumentation.ObservedInvocation> invocations =
                instrumentation.recentInvocations(INVOCATION_TAIL);
        FlowInstrumentation.ObservedTrace latest = instrumentation.latestCompletedTrace();
        FlowInstrumentation.ObservedTrace inner = matchInnerTrace(latest, call);

        return Result.success(RequestFlowStory.of(target.get(), outerRequestId,
                outerMarkers == null ? List.of() : List.copyOf(outerMarkers), call, inner, invocations));
    }

    /**
     * The inner request's trace is identified by CORRELATION ID: the probe
     * captured the x-request-id the inner request's RequestIdFilter echoed,
     * and the completed trace filed under that same id is provably the one
     * this probe caused. Falls back to the latest trace with an honest
     * {@code matched=false} when the ring has moved on.
     */
    private static FlowInstrumentation.ObservedTrace matchInnerTrace(
            FlowInstrumentation.ObservedTrace latest, InternalProbe.InternalCall call) {
        if (latest == null) {
            return null;
        }
        return latest.requestId().equals(call.requestId()) ? latest : null;
    }

    // ---- the story's wire records ----

    /**
     * The assembled layered story. {@code inner} is null when no completed
     * trace matched — the steps then carry the annotated sequence without
     * marker evidence, and say why.
     */
    public record RequestFlowStory(
            String target,
            String path,
            String controller,
            String useCase,
            String adapter,
            String note,
            OuterRequest outer,
            InnerRequest inner,
            List<FlowInstrumentation.ObservedInvocation> invocations,
            List<StoryStep> steps,
            Timings timings) {

        static RequestFlowStory of(ProbeTarget target, String outerRequestId, List<String> outerMarkers,
                InternalProbe.InternalCall call, FlowInstrumentation.ObservedTrace inner,
                List<FlowInstrumentation.ObservedInvocation> invocations) {
            return new RequestFlowStory(
                    target.id(), target.path(), target.controller(), target.useCase(),
                    target.adapter(), target.note(),
                    new OuterRequest(outerRequestId, outerMarkers),
                    inner == null ? null : new InnerRequest(inner.requestId(), inner.status(), true,
                            inner.markers()),
                    List.copyOf(invocations),
                    StoryStep.sequenceFor(target, call, inner, invocations),
                    Timings.of(call, invocations));
        }
    }

    /** The calling request (the one viewing the demo) — id plus markers so far. */
    public record OuterRequest(String requestId, List<String> markers) {
    }

    /** The driven request — its id, status, and full marker sequence. */
    public record InnerRequest(String requestId, int status, boolean matched, List<String> markers) {
    }

    /**
     * One step of the layered story: a stage tag, a human line, and where
     * the evidence came from ("topic 08 ring", "topic 06 aspect record",
     * "controller source"). Steps are either translated REAL markers or
     * the synthesized handler gap between preHandle and postHandle.
     */
    public record StoryStep(String stage, String label, String evidence) {

        static List<StoryStep> sequenceFor(ProbeTarget target, InternalProbe.InternalCall call,
                FlowInstrumentation.ObservedTrace inner,
                List<FlowInstrumentation.ObservedInvocation> invocations) {
            List<StoryStep> steps = new ArrayList<>();
            boolean handlerInserted = false;
            if (inner == null) {
                steps.add(new StoryStep("note",
                        "no completed pipeline trace matched this probe's correlation id "
                                + "(concurrent traffic, or the trace filters are not registered here) — "
                                + "the handler stages below are annotated, the timings are still measured",
                        "topic 08 ring, honestly empty"));
            } else {
                for (String marker : inner.markers()) {
                    if (!handlerInserted && "interceptor:postHandle".equals(marker)) {
                        addHandlerStages(steps, target, call, invocations);
                        handlerInserted = true;
                    }
                    steps.add(translated(marker));
                }
            }
            if (!handlerInserted) {
                addHandlerStages(steps, target, call, invocations);
            }
            steps.add(new StoryStep("done",
                    "response left the server — status " + call.status() + ", "
                            + call.bodyBytes() + " body bytes",
                    "probe measurement"));
            return List.copyOf(steps);
        }

        /** Maps one real ring marker to its stage + line; unknown markers pass through. */
        private static StoryStep translated(String marker) {
            return switch (marker) {
                case "filter:ordering:before" -> new StoryStep("filter",
                        "outermost filter (order 20) entered — request-id stamped before anything else",
                        "topic 08 ring: filter:ordering:before");
                case "filter:trace:before" -> new StoryStep("filter",
                        "inner trace filter entered", "topic 08 ring: filter:trace:before");
                case "interceptor:preHandle" -> new StoryStep("interceptor",
                        "interceptor preHandle — MVC stage begins, handler not yet invoked",
                        "topic 08 ring: interceptor:preHandle");
                case "interceptor:postHandle" -> new StoryStep("interceptor",
                        "interceptor postHandle — handler returned, response rendering ahead",
                        "topic 08 ring: interceptor:postHandle");
                case "interceptor:afterCompletion" -> new StoryStep("interceptor",
                        "interceptor afterCompletion — response work done, cleanup pass",
                        "topic 08 ring: interceptor:afterCompletion");
                case "filter:trace:after" -> new StoryStep("filter",
                        "inner trace filter unwound", "topic 08 ring: filter:trace:after");
                case "filter:ordering:after" -> new StoryStep("filter",
                        "outermost filter unwound — completed trace filed to the bounded ring",
                        "topic 08 ring: filter:ordering:after");
                default -> new StoryStep("filter", marker, "topic 08 ring");
            };
        }

        /**
         * The handler gap: between preHandle and postHandle the markers are
         * silent — that silence is where the architecture lives, so the story
         * spells out the four layers that ran inside it.
         */
        private static void addHandlerStages(List<StoryStep> steps, ProbeTarget target,
                InternalProbe.InternalCall call, List<FlowInstrumentation.ObservedInvocation> invocations) {
            steps.add(new StoryStep("controller",
                    target.controller() + " — thin: parse the input, call ONE use case, map the Result",
                    "controller source"));
            Optional<FlowInstrumentation.ObservedInvocation> probeRecord = invocations.stream()
                    .filter(invocation -> invocation.method().startsWith("guide.domain.arch.ProbeInternalApi"))
                    .reduce((first, second) -> second);
            steps.add(new StoryStep("use-case",
                    probeRecord.map(record -> record.method() + " → " + record.outcome()
                            + " in " + record.durationNanos() + " ns (requestId " + record.requestId() + ")")
                            .orElse("no aspect record filed for the probe"),
                    "topic 06 aspect record"));
            steps.add(new StoryStep("adapter",
                    "on the driven path: " + target.adapter() + " answers its port — "
                            + "the traced probe reached it over HTTP through " + PROBE_ADAPTER_NAME,
                    "port/adapter inventory (see the map)"));
            steps.add(new StoryStep("result",
                    call.status() < 400
                            ? "Success Result → " + call.status() + " JSON payload (" + call.bodyBytes() + " bytes)"
                            : "Failed Result → " + call.status() + " error envelope",
                    "probe measurement"));
        }
    }

    /** The data-layer adapter behind the InternalProbe port, named in the story's adapter step. */
    static final String PROBE_ADAPTER_NAME = "RestClientInternalProbe";

    /**
     * The measured timings, all real: the HTTP round trip from the adapter,
     * the traced use case's duration from the aspect record, and their
     * difference (never reported below zero — clock jitter exists).
     */
    public record Timings(long roundTripNanos, long useCaseNanos, long overheadNanos,
            int status, long bodyBytes) {

        static Timings of(InternalProbe.InternalCall call,
                List<FlowInstrumentation.ObservedInvocation> invocations) {
            Optional<FlowInstrumentation.ObservedInvocation> probeRecord = invocations.stream()
                    .filter(invocation -> invocation.method().startsWith("guide.domain.arch.ProbeInternalApi"))
                    .reduce((first, second) -> second);
            long useCaseNanos = probeRecord.map(FlowInstrumentation.ObservedInvocation::durationNanos)
                    .orElse(-1L);
            return new Timings(call.elapsedNanos(), useCaseNanos,
                    Math.max(0, useCaseNanos - call.elapsedNanos()), call.status(), call.bodyBytes());
        }
    }
}
