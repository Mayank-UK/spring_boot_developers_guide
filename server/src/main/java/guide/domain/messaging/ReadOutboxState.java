package guide.domain.messaging;

import java.util.List;

/**
 * Use case: the outbox demo's /state readout — the state machine counted,
 * plus the newest rows so the console can show a pending row appear, go
 * processed on relay, and park as dead when poisoned. Read-only by design;
 * the demo's EVIDENCE endpoint (what survived which crash).
 */
public class ReadOutboxState {

    private final OutboxStore store;

    public ReadOutboxState(OutboxStore store) {
        this.store = store;
    }

    /** Stats plus the newest rows, newest first. */
    public record StateView(OutboxStore.OutboxStats stats, List<OutboxStore.OutboxEntry> recent,
            boolean pollerRunning) {
    }

    public StateView execute(int recentLimit, boolean pollerRunning) {
        return new StateView(store.stats(), store.recent(recentLimit), pollerRunning);
    }
}
