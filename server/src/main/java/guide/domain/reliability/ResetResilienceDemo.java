package guide.domain.reliability;

/**
 * The demo's manual reset: breaker state and metrics back to CLOSED-empty,
 * the dial back to healthy, the flake counter rebased, the virtual clock
 * back to its anchor, the recent-calls memory cleared. Returns the fresh
 * status so the client repaints from one round trip.
 */
public final class ResetResilienceDemo {

    private final ResilientCaller caller;
    private final DownstreamTheater theater;
    private final VirtualTime time;
    private final ReadBreakerStatus readStatus;

    public ResetResilienceDemo(ResilientCaller caller, DownstreamTheater theater, VirtualTime time,
            ReadBreakerStatus readStatus) {
        this.caller = caller;
        this.theater = theater;
        this.time = time;
        this.readStatus = readStatus;
    }

    public ReadBreakerStatus.BreakerStatus execute() {
        caller.reset();
        theater.reset();
        time.reset();
        return readStatus.execute();
    }
}
