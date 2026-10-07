package guide.domain.arch;

import guide.core.aop.Traceable;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * The traced use case the tracer story is built around: drives ONE real
 * internal request through the {@link InternalProbe} port. Marked
 * {@link Traceable} — the ONE deliberate bit of topic-06 machinery on this
 * path — so the InvocationTraceAspect records this call (method, args,
 * outcome, durationNanos, the OUTER request's id from the MDC) into the
 * InvocationLog, where TraceRequestFlow reads it back as evidence.
 *
 * The aspect's duration brackets the whole probe: the record it files says
 * "the use case that performed an internal HTTP call took N nanoseconds",
 * while the adapter's own elapsedNanos says what the round trip itself took
 * — the difference is proxy + port + argument handling, usually noise, and
 * the story reports both honestly.
 *
 * Failures are VALUES, like every use case here: a probe that cannot reach
 * the server returns a Failed Result carrying an UNAVAILABLE failure, which
 * the edge maps onto the standard envelope — the aspect still files its
 * record (outcome ok — the call itself returned a value; the failure is
 * data, not an exception).
 */
@Traceable
public class ProbeInternalApi {

    private final InternalProbe probe;

    public ProbeInternalApi(InternalProbe probe) {
        this.probe = probe;
    }

    /** Drives the target's path over real HTTP; Result, never a throw. */
    public Result<InternalProbe.InternalCall> execute(ProbeTarget target) {
        try {
            return Result.success(probe.get(target.path()));
        } catch (RuntimeException unreachable) {
            return Result.failed(Failure.of(FailureKind.UNAVAILABLE,
                    "the internal probe could not reach " + target.path() + ": "
                            + unreachable.getMessage(),
                    Map.of("target", target.id(), "cause", unreachable.getClass().getSimpleName())));
        }
    }
}
