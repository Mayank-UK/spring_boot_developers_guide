package guide.domain.arch;

import java.util.List;

/**
 * Port: read (and reset) the two evidence rings the app already maintains —
 * topic 06's InvocationLog (aspect-recorded use-case invocations) and topic
 * 08's PipelineTraceLog (completed middleware traces). The rings are
 * machinery in guide.core; this port is the domain's view of them, so the
 * use cases never touch the rings directly and an adapter maps the shapes
 * (DTOs never leak past adapters, in either direction).
 */
public interface FlowInstrumentation {

    /**
     * Drops every recorded invocation — the demo discipline the aop demos
     * use too, so the tail read after a probe is exactly the probe's own
     * evidence.
     */
    void resetInvocations();

    /** The last {@code limit} invocations, oldest first. */
    List<ObservedInvocation> recentInvocations(int limit);

    /** The most recently completed request trace, or null when none exists yet. */
    ObservedTrace latestCompletedTrace();

    /** One aspect-recorded invocation (topic 06's InvocationRecord, mapped). */
    record ObservedInvocation(String method, String argsSummary, String outcome,
            long durationNanos, String requestId) {
    }

    /** One completed middleware trace (topic 08's CompletedTrace, mapped). */
    record ObservedTrace(String requestId, String method, String path, int status,
            List<String> markers) {
    }
}
