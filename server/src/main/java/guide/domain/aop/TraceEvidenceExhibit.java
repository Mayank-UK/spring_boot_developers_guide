package guide.domain.aop;

import guide.core.aop.InvocationRecord;
import java.util.List;

/**
 * The aop:trace demo's response: what the two sample steps returned and
 * threw, the exact {@link InvocationRecord}s the InvocationTraceAspect filed
 * for each, and the raw log tail they came from. Derivation lives here (pure,
 * testable): pick the records for the sample steps out of a tail, tolerating
 * their absence — which is exactly what happens when the steps are invoked
 * WITHOUT a proxy in front of them, and is itself part of the lesson.
 *
 * Durations are wall-clock nanoseconds and therefore machine-dependent:
 * consumers assert presence and {@code >= 0}, never equality.
 */
public record TraceEvidenceExhibit(
        String stepResult,
        String failureMessage,
        InvocationRecord okRecord,
        InvocationRecord exceptionRecord,
        List<InvocationRecord> logTail) {

    public TraceEvidenceExhibit {
        logTail = logTail == null ? List.of() : List.copyOf(logTail);
    }

    /** Assembles the exhibit from the demo run's outcomes and the log tail. */
    public static TraceEvidenceExhibit of(String stepResult, String failureMessage,
            List<InvocationRecord> tail) {
        List<InvocationRecord> safeTail = tail == null ? List.of() : List.copyOf(tail);
        InvocationRecord ok = firstFor(safeTail, ".succeedingStep");
        InvocationRecord failure = firstFor(safeTail, ".failingStep");
        return new TraceEvidenceExhibit(stepResult, failureMessage, ok, failure, safeTail);
    }

    private static InvocationRecord firstFor(List<InvocationRecord> tail, String methodSuffix) {
        return tail.stream()
                .filter(record -> record.method() != null && record.method().endsWith(methodSuffix))
                .findFirst()
                .orElse(null);
    }
}
