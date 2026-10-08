package guide.domain.reliability;

import guide.core.result.Result;
import guide.domain.reset.ResettableDemo;
import java.util.Objects;

/**
 * ResettableDemo adapter for the reliability demos (topic 30 — breaker,
 * downstream theater, virtual clock). Delegation only: POST
 * /api/demo/reliability/reset runs the SAME {@link ResetResilienceDemo}
 * use case this adapter runs; the pinned endpoint and its response shape
 * are untouched.
 */
public final class ReliabilityDemoReset implements ResettableDemo {

    /** The shared sweep's stable key for this area. */
    static final String AREA = "reliability";

    private final ResetResilienceDemo resetResilienceDemo;

    public ReliabilityDemoReset(ResetResilienceDemo resetResilienceDemo) {
        this.resetResilienceDemo = Objects.requireNonNull(resetResilienceDemo, "resetResilienceDemo");
    }

    @Override
    public String id() {
        return AREA;
    }

    @Override
    public Result<String> reset() {
        resetResilienceDemo.execute();
        return Result.success("breaker, dial, clock and memory back to the anchor state");
    }
}
