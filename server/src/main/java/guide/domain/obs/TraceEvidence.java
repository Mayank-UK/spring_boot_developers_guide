package guide.domain.obs;

import java.util.List;

/**
 * What the trace demo proves with, in one response: the trace id, the W3C
 * header that carried it across the self-call, what the hop observed and
 * did, the finished spans of that trace with timings, and the log lines
 * whose MDC carried the SAME id — one id across every hop, as evidence
 * rather than as a claim.
 */
public record TraceEvidence(
        String traceId,
        String traceparentSent,
        HopProof hop,
        List<TraceSpan> spans,
        List<LoggedEvent> logs) {

    /** The hop's side of the story: the header it received, the trace id its thread carried, the work it did. */
    public record HopProof(String traceparentSeen, String traceIdFromMdc, RunTracedHop.HopFacts facts) {
    }
}
