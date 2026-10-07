package guide.domain.spring;

import guide.core.result.Result;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Use case: report the running application's own boot story (topic 04's
 * spring:app-info demo). No input, no expected failure mode — kept as a use
 * case anyway so the controller stays thin and the wiring mirrors every other
 * endpoint: controller → use case → Result. The clock is injected because
 * uptime is derived per request; a fixed supplier makes the domain test
 * deterministic.
 */
public class ShowAppInfo {

    private final AppInfoSource source;
    private final LongSupplier clock;

    public ShowAppInfo(AppInfoSource source, LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result<AppInfoExhibit> execute() {
        AppInfoFacts facts = Objects.requireNonNull(source.facts(), "facts");
        return Result.success(AppInfoExhibit.of(facts, clock.getAsLong()));
    }
}
