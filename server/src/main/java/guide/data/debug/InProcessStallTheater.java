package guide.data.debug;

import guide.domain.debug.StallThreadView;
import guide.domain.debug.StallTheater;
import guide.domain.debug.ThreadDiagnostics;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The stall demo's engine: parks named daemon threads in exactly the states
 * a thread dump reads, deterministically and repeatably.
 *
 * KIND "lock" — the classic two-thread story:
 * - "debug-stall-owner" enters a synchronized block, signals a latch (so
 *   the theater KNOWS the monitor is held), then sleeps in a loop —
 *   TIMED_WAITING while HOLDING the monitor, because Thread.sleep does not
 *   release a lock;
 * - "debug-stall-victim" then tries to enter the SAME block and parks at
 *   the monitor — BLOCKED, and every dump names the owner.
 * The latch plus a bounded wait for the victim to reach BLOCKED make the
 * whole shape deterministic: when start() returns, the teaching state is
 * already on stage, never racing.
 *
 * KIND "spin" — one thread busy-looping until stopped: RUNNABLE forever,
 * the state that looks healthy and burns a core.
 *
 * stop() flips one volatile, interrupts the sleepers, and JOINs every
 * thread — no monitor and no thread survives a stop, so the demo cannot
 * leak into the rest of the app (and every thread is a daemon anyway, so
 * even a forgotten stop cannot block JVM shutdown).
 */
public final class InProcessStallTheater implements StallTheater {

    static final String OWNER_NAME = "debug-stall-owner";
    static final String VICTIM_NAME = "debug-stall-victim";
    static final String SPINNER_NAME = "debug-stall-spinner";

    /** How long start() may wait for the parked states to settle (they take milliseconds). */
    private static final long SETTLE_TIMEOUT_MS = 5_000;

    /** How long stop() waits for each thread to die before giving up on the join. */
    private static final long JOIN_TIMEOUT_MS = 5_000;

    private final Object monitor = new Object();
    private final ThreadDiagnostics diagnostics;

    /** The engine's state lock — never the same object as the demo monitor. */
    private final Object state = new Object();

    private volatile boolean stopRequested;
    private volatile String runningKind = "";
    private volatile long startedAtMillis;
    private volatile long parkedSinceNs;
    private volatile long lastParkedMillis;
    private volatile long spinSink;
    private Thread owner;
    private Thread victim;
    private Thread spinner;

    public InProcessStallTheater(ThreadDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean isRunning() {
        return !runningKind.isEmpty();
    }

    @Override
    public String kind() {
        return runningKind;
    }

    @Override
    public long startedAtMillis() {
        return startedAtMillis;
    }

    @Override
    public long lastParkedMillis() {
        return lastParkedMillis;
    }

    @Override
    public void start(String kind) {
        synchronized (state) {
            if (!runningKind.isEmpty()) {
                throw new IllegalStateException("a stall is already parked: " + runningKind);
            }
            stopRequested = false;
            try {
                if ("lock".equals(kind)) {
                    startLockStall();
                } else {
                    startSpinStall();
                }
            } catch (RuntimeException neverSettled) {
                // a half-started stall must not outlive its own failure
                stopRequested = true;
                for (Thread parked : Arrays.asList(owner, victim, spinner)) {
                    if (parked != null) {
                        parked.interrupt();
                    }
                }
                joinQuietly(owner);
                joinQuietly(victim);
                joinQuietly(spinner);
                owner = null;
                victim = null;
                spinner = null;
                throw neverSettled;
            }
            parkedSinceNs = System.nanoTime();
            startedAtMillis = System.currentTimeMillis();
            lastParkedMillis = 0;
            runningKind = kind;
        }
    }

    private void startLockStall() {
        CountDownLatch monitorHeld = new CountDownLatch(1);
        owner = new Thread(() -> {
            synchronized (monitor) {
                monitorHeld.countDown();
                while (!stopRequested) {
                    try {
                        // asleep, but STILL HOLDING the monitor — sleep releases nothing
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, OWNER_NAME);
        owner.setDaemon(true);
        owner.start();
        await(monitorHeld, "the owner never took the monitor");
        victim = new Thread(() -> {
            // reached only AFTER the owner released the monitor; until then BLOCKED at entry
            synchronized (monitor) {
                if (!stopRequested) {
                    // the owner only releases on stop — if we are in here early, the story broke
                    throw new IllegalStateException("victim entered the monitor before stop");
                }
            }
        }, VICTIM_NAME);
        victim.setDaemon(true);
        victim.start();
        awaitState(victim, Thread.State.BLOCKED, "the victim never reached BLOCKED");
    }

    private void startSpinStall() {
        spinner = new Thread(() -> {
            while (!stopRequested) {
                // a volatile write the JIT cannot elide: honest busy-work, RUNNABLE forever
                spinSink = spinSink * 31 + 1;
            }
        }, SPINNER_NAME);
        spinner.setDaemon(true);
        spinner.start();
        awaitState(spinner, Thread.State.RUNNABLE, "the spinner never reached RUNNABLE");
        awaitSpinWork();
    }

    /** Determinism: by the time start() returns, the spinner has provably done work. */
    private void awaitSpinWork() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_TIMEOUT_MS);
        while (spinSink == 0) {
            if (spinner.getState() == Thread.State.TERMINATED) {
                throw new IllegalStateException("the spinner died before doing any work");
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the spinner never did any visible work");
            }
        }
    }

    @Override
    public long stop() {
        synchronized (state) {
            if (runningKind.isEmpty()) {
                return 0;
            }
            stopRequested = true;
            for (Thread parked : Arrays.asList(owner, victim, spinner)) {
                if (parked != null) {
                    parked.interrupt();
                }
            }
            joinQuietly(owner);
            joinQuietly(victim);
            joinQuietly(spinner);
            long parkedMillis = (System.nanoTime() - parkedSinceNs) / 1_000_000;
            owner = null;
            victim = null;
            spinner = null;
            runningKind = "";
            lastParkedMillis = parkedMillis;
            return parkedMillis;
        }
    }

    @Override
    public List<StallThreadView> inspect() {
        synchronized (state) {
            return diagnostics.snapshotAll(Arrays.stream(new Thread[] {owner, victim, spinner})
                    .filter(thread -> thread != null)
                    .toList());
        }
    }

    /** The spin loop's accumulated work — proof the RUNNABLE thread was doing something. */
    public long spinSink() {
        return spinSink;
    }

    private static void await(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(failure);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure, interrupted);
        }
    }

    /** Bounded poll until a parked thread reaches its teaching state — determinism for the API contract. */
    private static void awaitState(Thread thread, Thread.State wanted, String failure) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_TIMEOUT_MS);
        while (thread.getState() != wanted) {
            if (thread.getState() == Thread.State.TERMINATED) {
                throw new IllegalStateException(failure + " — the thread died first");
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(failure + " (stuck in " + thread.getState() + ")");
            }
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
