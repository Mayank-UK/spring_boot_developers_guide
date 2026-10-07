package guide.domain.debug;

import java.util.List;

/**
 * The stall demo's engine port: park named worker threads in states a thread
 * dump can read, deterministically and repeatably.
 *
 * Contract (what the demo's honesty rests on):
 * - {@link #start(String)} blocks until the stall threads have REACHED their
 *   parked states (the victim is BLOCKED, the spinner RUNNABLE) — when it
 *   returns, a snapshot is guaranteed to show the teaching state, never a
 *   racing half-start;
 * - {@link #stop()} wakes every parked thread, JOINS it, and returns the
 *   millis the stall was parked — no thread and no monitor survives a stop,
 *   so the demo never leaks into the rest of the app;
 * - parked threads are DAEMONS: even a forgotten stop cannot block JVM
 *   shutdown;
 * - the engine NEVER touches request threads — parking a Tomcat worker is
 *   the self-DoS this topic warns about, not a demo.
 */
public interface StallTheater {

    /** True between a successful {@link #start} and the following {@link #stop}. */
    boolean isRunning();

    /** The parked kind — "lock" or "spin"; empty when idle. */
    String kind();

    /** Epoch millis of the current (or last) start; 0 when never started. */
    long startedAtMillis();

    /** Millis the previous stall was parked; 0 while it runs or when never run. */
    long lastParkedMillis();

    /**
     * Parks the stall threads for {@code kind} and returns once they are in
     * their parked states. Throws IllegalStateException when a stall is
     * already running (the use case maps that to a typed conflict).
     */
    void start(String kind);

    /** Wakes and joins every parked thread; a no-op returning 0 when idle. */
    long stop();

    /** The theater's parked threads as a dump would show them, top-down stacks. */
    List<StallThreadView> inspect();
}
