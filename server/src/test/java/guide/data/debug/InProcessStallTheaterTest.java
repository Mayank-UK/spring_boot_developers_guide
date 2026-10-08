package guide.data.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.debug.StallThreadView;
import guide.domain.debug.ThreadDiagnostics;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stall engine's lifecycle contract, directly: parked states are
 * deterministic the moment start() returns, stop() leaves NO thread and NO
 * monitor behind, double-start is rejected, and stop on an idle theater is
 * a no-op. This is the machinery the demo's honesty rests on — if the
 * theater leaked or raced, the teaching snapshot would lie.
 */
class InProcessStallTheaterTest {

    private final InProcessStallTheater theater = new InProcessStallTheater(new ThreadDiagnostics());

    @AfterEach
    void stopAnythingLeftRunning() {
        theater.stop();
    }

    @Test
    void lockStallParksOwnerHoldingAndVictimBlockedDeterministically() {
        theater.start("lock");

        assertTrue(theater.isRunning());
        assertEquals("lock", theater.kind());

        Map<String, StallThreadView> byName = viewsByName();
        StallThreadView owner = byName.get(InProcessStallTheater.OWNER_NAME);
        StallThreadView victim = byName.get(InProcessStallTheater.VICTIM_NAME);
        assertNotNull(owner, "the owner thread must be in the snapshot");
        assertNotNull(victim, "the victim thread must be in the snapshot");
        assertEquals("TIMED_WAITING", owner.state());
        assertEquals("BLOCKED", victim.state());
        assertNotNull(victim.lock(), "the BLOCKED victim must name the monitor it waits for");
        assertEquals(InProcessStallTheater.OWNER_NAME, victim.lockOwner(),
                "the dump must name the lock HOLDER — the whole point of the demo");
        assertFalse(victim.stack().isEmpty(), "a dump line carries the stack, not just the state");
        assertTrue(victim.stack().getFirst().startsWith("at "),
                "frames render in dump form: 'at pkg.Class.method(File.java:line)'");
    }

    @Test
    void spinStallIsRunnableForever() {
        theater.start("spin");

        Map<String, StallThreadView> byName = viewsByName();
        StallThreadView spinner = byName.get(InProcessStallTheater.SPINNER_NAME);
        assertNotNull(spinner);
        assertEquals("RUNNABLE", spinner.state());
        // the sink is a wrapping accumulator — non-zero is the proof of work
        assertNotEquals(0L, theater.spinSink(), "the spinner was really doing work while parked");
    }

    @Test
    void stopJoinsEveryThreadAndClearsTheStageCompletely() {
        theater.start("lock");
        theater.stop();

        assertFalse(theater.isRunning());
        assertEquals("", theater.kind());
        assertTrue(theater.lastParkedMillis() >= 0);
        assertTrue(theater.inspect().isEmpty(), "no thread survives the stop");
        assertEquals(0, liveStallThreads(), "no debug-stall thread is left alive in the JVM");
    }

    @Test
    void theStageIsRepeatableAndStopIsIdempotentWhenIdle() {
        assertEquals(0, theater.stop(), "stopping an idle theater is a no-op, not an error");
        theater.start("lock");
        theater.stop();
        theater.start("spin");
        theater.stop();
        theater.start("lock");
        assertEquals("lock", theater.kind(), "a third start after two full cycles works");
        theater.stop();
    }

    @Test
    void doubleStartIsRejectedWhileRunning() {
        theater.start("lock");
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> theater.start("spin"));
        assertEquals("lock", theater.kind(), "the running stall is untouched by the rejected start");
        assertTrue(rejected.getMessage().contains("already parked"));
    }

    private Map<String, StallThreadView> viewsByName() {
        return theater.inspect().stream()
                .collect(Collectors.toMap(StallThreadView::name, Function.identity()));
    }

    private static long liveStallThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("debug-stall-"))
                .count();
    }
}
