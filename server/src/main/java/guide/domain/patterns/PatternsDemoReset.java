package guide.domain.patterns;

import guide.core.result.Result;
import guide.domain.reset.ResettableDemo;
import java.util.Objects;

/**
 * ResettableDemo adapter for the arch:saga / arch:eventstore demos (topic
 * 44). This area ALREADY owned its resets — POST /api/demo/arch/saga/reset
 * and /api/demo/arch/eventstore/reset call the same two engine methods —
 * so this adapter only DELEGATES; it owns no logic of its own and the
 * pinned endpoints keep working unchanged beside it.
 */
public final class PatternsDemoReset implements ResettableDemo {

    /** The shared sweep's stable key for this area. */
    static final String AREA = "patterns";

    private final OrderSagas orderSagas;
    private final EventStore eventStore;

    public PatternsDemoReset(OrderSagas orderSagas, EventStore eventStore) {
        this.orderSagas = Objects.requireNonNull(orderSagas, "orderSagas");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    }

    @Override
    public String id() {
        return AREA;
    }

    @Override
    public Result<String> reset() {
        orderSagas.reset();
        eventStore.reset();
        return Result.success("saga world and event log back to constructed state");
    }
}
