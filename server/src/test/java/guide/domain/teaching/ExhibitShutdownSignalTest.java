package guide.domain.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ExhibitShutdownSignal behavior: register → remove reports cleanly (and the
 * removal is real — removeShutdownHook returns true only for a registered
 * hook), with a sensible name for blank labels.
 */
class ExhibitShutdownSignalTest {

    @Test
    void registersAndRemovesTheHookCleanly() {
        String report = ExhibitShutdownSignal.registerAndRemove("cache-flush");

        assertTrue(report.contains("shutdown-cache-flush"));
        assertTrue(report.contains("removed"), report);
        assertTrue(report.contains("will not run at exit"), report);
    }

    @Test
    void blankLabelFallsBackToDemo() {
        String report = ExhibitShutdownSignal.registerAndRemove(" ");
        assertTrue(report.startsWith("hook \"shutdown-demo\""));
    }

    @Test
    void repeatedRegistrationIsSafe() {
        String first = ExhibitShutdownSignal.registerAndRemove("a");
        String second = ExhibitShutdownSignal.registerAndRemove("a");
        assertEquals(first.length(), second.length());
    }
}
