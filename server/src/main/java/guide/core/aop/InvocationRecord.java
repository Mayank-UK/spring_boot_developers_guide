package guide.core.aop;

/**
 * One completed interception: what was called, with what (capped) argument
 * summary, how it ended, how long it took, and under which request id — the
 * five facts an operations person needs from a trace line. Immutable by
 * design; produced only by {@link InvocationTraceAspect}, stored in
 * {@link InvocationLog}.
 *
 * The wire contract (this record serializes straight into the aop:trace and
 * aop:self-invocation demos):
 * - {@code method} — declaring type + method name, e.g.
 *   {@code guide.domain.teaching.TracedOrderWorkflow.placeOrder}
 * - {@code argsSummary} — a capped, never-full-payload rendering of the args
 * - {@code outcome} — {@code ok} or {@code exception:<SimpleTypeName>}
 * - {@code durationNanos} — wall time around the target call; assert as
 *   present and {@code >= 0}, never an exact value
 * - {@code requestId} — the RequestIdFilter correlation id when the call ran
 *   on an HTTP thread, else null (background jobs, startup, tests)
 */
public record InvocationRecord(
        String method,
        String argsSummary,
        String outcome,
        long durationNanos,
        String requestId) {
}
