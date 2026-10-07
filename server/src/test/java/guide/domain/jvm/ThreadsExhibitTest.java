package guide.domain.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ThreadsExhibit behavior: structure is deterministic (pool bounds the
 * platform names, virtual gives one thread per task, the carrier shows
 * through), wall time is reported but only ever asserted as positive —
 * never to an exact value.
 */
class ThreadsExhibitTest {

    private final ThreadsExhibit exhibit = new ThreadsExhibit();

    @Test
    void platformRunIsBoundedByThePool() {
        ThreadsExhibit.RunReport report = exhibit.runPlatform(24);

        assertEquals("platform", report.mode());
        assertEquals(24, report.tasks());
        assertEquals(ThreadsExhibit.PLATFORM_POOL_SIZE, report.distinctThreadNames());
        assertFalse(report.virtualThreadsObserved());
        assertFalse(report.carrierObserved());
        assertTrue(report.wallTimeMillis() > 0, "24 tasks on 4 threads must take at least one wave");
        assertTrue(report.sampleThreadNames().size() <= 5);
        for (String name : report.sampleThreadNames()) {
            assertTrue(name.startsWith("Thread["), "platform names are pool threads: " + name);
        }
    }

    @Test
    void singleTaskUsesExactlyOnePlatformThread() {
        ThreadsExhibit.RunReport report = exhibit.runPlatform(1);
        assertEquals(1, report.distinctThreadNames());
    }

    @Test
    void virtualRunGivesOneThreadPerTaskAndShowsTheCarrier() {
        ThreadsExhibit.RunReport report = exhibit.runVirtual(24);

        assertEquals("virtual", report.mode());
        assertEquals(24, report.tasks());
        assertEquals(24, report.distinctThreadNames(), "one virtual thread per task");
        assertTrue(report.virtualThreadsObserved());
        assertTrue(report.carrierObserved(), "toString on a mounted virtual thread names its carrier");
        assertTrue(report.carrierSample().contains("ForkJoinPool"),
                "the carrier is a ForkJoinPool worker: " + report.carrierSample());
        assertTrue(report.wallTimeMillis() >= ThreadsExhibit.BLOCK_MILLIS);
        assertTrue(report.sampleThreadNames().stream().allMatch(name -> name.startsWith("VirtualThread")));
    }

    @Test
    void comparisonRunsBothModelsAndSummarizes() {
        ThreadsExhibit.ComparisonReport comparison = exhibit.compare(16);

        assertEquals(16, comparison.tasks());
        assertEquals("platform", comparison.platform().mode());
        assertEquals("virtual", comparison.virtual().mode());
        assertEquals(ThreadsExhibit.PLATFORM_POOL_SIZE, comparison.platform().distinctThreadNames());
        assertEquals(16, comparison.virtual().distinctThreadNames());
        assertTrue(comparison.summary().contains("waves"));
        assertTrue(comparison.summary().contains("16"));
    }

    @Test
    void namedVirtualThreadBuilderNamesAndRuns() {
        // JDK 25's virtual toString is VirtualThread[#id,name]/state@carrier —
        // the NAME we set shows inside it, alongside id and carrier.
        String observed = ThreadsExhibit.namedVirtualHello();
        assertTrue(observed.contains("guide-worker-0"), observed);
        assertTrue(observed.startsWith("VirtualThread["), observed);
    }
}
