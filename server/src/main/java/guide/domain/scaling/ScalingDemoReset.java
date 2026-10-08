package guide.domain.scaling;

import guide.core.result.Result;
import guide.domain.reset.ResettableDemo;
import java.util.Objects;

/**
 * ResettableDemo adapter for the scaling:stateless demo (topic 40). Pure
 * delegation: GET /api/demo/scaling/stateless/reset calls the same
 * {@link ProbeStatelessness#reset()} — this adapter just registers that
 * behavior behind the shared sweep, leaving the pinned endpoint alone.
 */
public final class ScalingDemoReset implements ResettableDemo {

    /** The shared sweep's stable key for this area. */
    static final String AREA = "scaling";

    private final ProbeStatelessness probeStatelessness;

    public ScalingDemoReset(ProbeStatelessness probeStatelessness) {
        this.probeStatelessness = Objects.requireNonNull(probeStatelessness, "probeStatelessness");
    }

    @Override
    public String id() {
        return AREA;
    }

    @Override
    public Result<String> reset() {
        probeStatelessness.reset();
        return Result.success("probe fleet zeroed, router cursor rewound");
    }
}
