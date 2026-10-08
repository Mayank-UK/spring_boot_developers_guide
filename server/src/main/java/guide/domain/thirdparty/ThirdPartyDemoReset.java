package guide.domain.thirdparty;

import guide.core.result.Result;
import guide.domain.reset.ResettableDemo;
import java.util.Objects;

/**
 * ResettableDemo adapter for the thirdparty:hub demo (topic 43).
 * Delegation only: POST /api/demo/thirdparty/reset calls the same
 * {@link ThirdPartyHub#reset()} (quota, counters, adapter posture) — the
 * pinned endpoint keeps its own response shape beside this registration.
 */
public final class ThirdPartyDemoReset implements ResettableDemo {

    /** The shared sweep's stable key for this area. */
    static final String AREA = "thirdparty";

    private final ThirdPartyHub hub;

    public ThirdPartyDemoReset(ThirdPartyHub hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @Override
    public String id() {
        return AREA;
    }

    @Override
    public Result<String> reset() {
        hub.reset();
        return Result.success("hub quota, counters and adapter posture back to constructed state");
    }
}
