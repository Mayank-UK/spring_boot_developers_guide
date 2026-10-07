package guide.domain.reliability;

/**
 * Fires one call through the whole stack (breaker → retry/timeout → the
 * downstream theater) and returns the report. No parameters to validate —
 * the theater's DIAL decides what the call meets, which is the demo's
 * point: the caller's code never changes; only the downstream's behavior
 * does.
 */
public final class CallDownstream {

    private final ResilientCaller caller;

    public CallDownstream(ResilientCaller caller) {
        this.caller = caller;
    }

    public ResilientCaller.CallReport execute() {
        return caller.call();
    }
}
