package guide.domain.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import guide.domain.teaching.ExhibitConcurrency.Counter;
import org.junit.jupiter.api.Test;

/**
 * The concurrency exhibit is real code — deterministic, so provable. These
 * tests are also what earns it the right to be quoted as teaching source.
 */
class ExhibitConcurrencyTest {

    @Test
    void guardedSumCountsEveryIncrementUnderTheLock() throws Exception {
        assertEquals(40_000, ExhibitConcurrency.guardedSum(4, 10_000));
    }

    @Test
    void pooledSumCollectsEveryFutureFromThePool() throws Exception {
        assertEquals(80, ExhibitConcurrency.pooledSum(8, 10));
    }

    @Test
    void pipelineComposesStagesThenRecoversFailure() {
        assertEquals(10, ExhibitConcurrency.pipeline(3, false)); // 3*3 -> +1
        assertEquals(-1, ExhibitConcurrency.pipeline(3, true)); // recovered
    }

    @Test
    void virtualThreadSumRunsOneTaskPerCheapThread() throws Exception {
        assertEquals(100, ExhibitConcurrency.virtualThreadSum(100));
    }

    @Test
    void counterIncrementReturnsANewImmutableSnapshot() {
        Counter one = new Counter(1);

        assertEquals(2, one.increment().value());
        assertEquals(1, one.value()); // the original is untouched
    }
}
