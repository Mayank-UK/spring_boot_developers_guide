package guide.domain.reliability;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * The manual ops rail onto the breaker: force the OPEN → HALF_OPEN
 * transition instead of waiting out the timer. Resilience4j's own wait
 * runs on the REAL clock (inside the adapter, where the library lives), so
 * deterministic demos and tests (and impatient operators) use this door —
 * it is the same call an actuator-style management endpoint would make in
 * production.
 *
 * Guarded: only an OPEN breaker has a probe to force. Anything else is the
 * typed validation envelope ("nothing to probe"), so the demo never leans
 * on any library's internal exception text.
 */
public final class ForceHalfOpenProbe {

    private final BreakerPort breaker;

    public ForceHalfOpenProbe(BreakerPort breaker) {
        this.breaker = breaker;
    }

    public Result<String> execute() {
        String state = breaker.state();
        if (!"OPEN".equals(state)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "nothing to probe — the breaker is " + state
                            + ", only an OPEN breaker can be forced to HALF_OPEN",
                    Map.of("state", state)));
        }
        breaker.forceHalfOpen();
        return Result.success(breaker.state());
    }
}
