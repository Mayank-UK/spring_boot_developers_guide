package guide.domain.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * Use case: snapshot the stall right now and READ it — the report pairs each
 * thread's dump-shaped facts (name, state, lock, owner, stack) with narration
 * derived from those facts, the way an experienced engineer reads a dump out
 * loud. The GET endpoint and the demo's refresh both run through here; it
 * never fails, because observing threads is not a failure mode.
 */
public final class ReadStallThreads {

    private final StallTheater theater;
    private final ThreadDiagnostics diagnostics;

    public ReadStallThreads(StallTheater theater, ThreadDiagnostics diagnostics) {
        this.theater = theater;
        this.diagnostics = diagnostics;
    }

    public StallReport execute() {
        List<StallThreadView> threads = theater.inspect();
        int liveThreadCount = diagnostics.liveThreadCount();
        return new StallReport(theater.isRunning(), theater.kind(), theater.startedAtMillis(),
                theater.lastParkedMillis(), liveThreadCount, threads,
                reading(theater, threads, liveThreadCount));
    }

    /** The narration: what the observed states MEAN, one line per fact worth reading. */
    private static List<String> reading(StallTheater theater, List<StallThreadView> threads, int liveThreadCount) {
        List<String> lines = new ArrayList<>();
        if (!theater.isRunning()) {
            long parked = theater.lastParkedMillis();
            lines.add(parked > 0
                    ? "no stall is parked — the previous one ran for " + parked + "ms; start another and snapshot while it runs"
                    : "no stall is parked — start one, then snapshot WHILE it runs: the state you read is the state jstack would print");
            return lines;
        }
        for (StallThreadView thread : threads) {
            switch (thread.state()) {
                case "BLOCKED" -> lines.add(thread.name() + " is BLOCKED, waiting to lock " + thread.lock()
                        + " held by " + thread.lockOwner()
                        + " — the dump NAMES the holder, which is the whole trick");
                case "TIMED_WAITING" -> lines.add(thread.name() + " is TIMED_WAITING — asleep in a timed park"
                        + (thread.lock() == null ? "" : " on " + thread.lock())
                        + ", and if it holds a monitor, sleeping does NOT release it");
                case "WAITING" -> lines.add(thread.name() + " is WAITING — parked indefinitely until something"
                        + " notifies or unparks it");
                case "RUNNABLE" -> lines.add(thread.name() + " is RUNNABLE — runnable-forever is its own smell:"
                        + " sample the stack twice and it is still here, the poor man's profiler");
                default -> lines.add(thread.name() + " is " + thread.state());
            }
        }
        lines.add(threads.size() + " of " + liveThreadCount
                + " JVM threads shown — a real dump lists every line; you learn to read the ones that matter");
        return lines;
    }
}
