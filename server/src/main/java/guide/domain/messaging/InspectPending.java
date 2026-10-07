package guide.domain.messaging;

/**
 * Use case: the pending inspection — XPENDING's summary and per-entry
 * detail, surfaced for the console. Kept as its own (tiny) use case by the
 * house rule that controllers call use cases, never adapters — the whole
 * point of this read is showing WHERE the entries are (consumer, idle,
 * delivery count), which is protocol state, not business state.
 */
public class InspectPending {

    private final EventStream stream;

    public InspectPending(EventStream stream) {
        this.stream = stream;
    }

    /** The typed pass-through: the view IS the lesson. */
    public EventStream.PendingView execute() {
        return stream.pending();
    }
}
