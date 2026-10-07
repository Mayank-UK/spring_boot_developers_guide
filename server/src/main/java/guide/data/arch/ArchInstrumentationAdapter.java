package guide.data.arch;

import guide.core.aop.InvocationLog;
import guide.core.middleware.PipelineTraceLog;
import guide.domain.arch.FlowInstrumentation;
import java.util.List;
import java.util.Optional;

/**
 * Adapter: answers the FlowInstrumentation port from the two evidence rings
 * the app already maintains — topic 06's InvocationLog (aspect records) and
 * topic 08's PipelineTraceLog (completed middleware traces). The rings are
 * guide.core machinery; this adapter maps their records into the domain's
 * observation records so no core type crosses the port — the same
 * DTOs-stay-in-their-layer rule the invariant applies to repositories.
 */
public final class ArchInstrumentationAdapter implements FlowInstrumentation {

    private final InvocationLog invocationLog;
    private final PipelineTraceLog pipelineTraceLog;

    public ArchInstrumentationAdapter(InvocationLog invocationLog, PipelineTraceLog pipelineTraceLog) {
        this.invocationLog = invocationLog;
        this.pipelineTraceLog = pipelineTraceLog;
    }

    @Override
    public void resetInvocations() {
        invocationLog.clear();
    }

    @Override
    public List<ObservedInvocation> recentInvocations(int limit) {
        return invocationLog.tail(limit).stream()
                .map(record -> new ObservedInvocation(record.method(), record.argsSummary(),
                        record.outcome(), record.durationNanos(), record.requestId()))
                .toList();
    }

    @Override
    public ObservedTrace latestCompletedTrace() {
        return Optional.ofNullable(pipelineTraceLog.latest())
                .map(trace -> new ObservedTrace(trace.requestId(), trace.method(), trace.path(),
                        trace.status(), trace.markers()))
                .orElse(null);
    }
}
