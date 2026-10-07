package guide.domain.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.aop.InvocationLog;
import guide.core.aop.InvocationRecord;
import guide.core.result.Failed;
import guide.core.result.Success;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ShowTraceEvidence and TraceEvidenceExhibit behavior: with RAW (unproxied)
 * steps the demo still runs and reports honestly that nothing was recorded
 * (no proxy, no interception — itself the lesson), and the exhibit mapping
 * picks the right records out of a populated tail.
 */
class ShowTraceEvidenceTest {

    /** Unwraps a Success the way the controller does. */
    private static TraceEvidenceExhibit run(ShowTraceEvidence useCase) {
        return switch (useCase.execute()) {
            case Success<TraceEvidenceExhibit> success -> success.value();
            case Failed<TraceEvidenceExhibit> failed -> throw new AssertionError(failed.failure().toString());
        };
    }

    @Test
    void withoutAProxyTheRunCompletesButRecordsNothing() {
        InvocationLog log = new InvocationLog(8);
        ShowTraceEvidence showTraceEvidence = new ShowTraceEvidence(new TracedSampleSteps(), log);

        TraceEvidenceExhibit exhibit = run(showTraceEvidence);

        assertEquals("processed[demo-seed]", exhibit.stepResult());
        assertEquals("deliberate failure to demonstrate outcome=exception", exhibit.failureMessage());
        assertEquals(List.of(), exhibit.logTail(), "raw calls are not intercepted — empty log");
        assertNull(exhibit.okRecord());
        assertNull(exhibit.exceptionRecord());
    }

    @Test
    void exhibitMappingPicksTheSampleStepsOutOfAPopulatedTail() {
        InvocationRecord foreign = new InvocationRecord(
                "guide.domain.spring.ShowAppInfo.execute", "()",
                "ok", 1_000L, "req-1");
        InvocationRecord ok = new InvocationRecord(
                "guide.domain.aop.TracedSampleSteps.succeedingStep", "(demo-seed)",
                "ok", 250L, "req-2");
        InvocationRecord failure = new InvocationRecord(
                "guide.domain.aop.TracedSampleSteps.failingStep", "()",
                "exception:IllegalStateException", 100L, "req-2");

        TraceEvidenceExhibit exhibit = TraceEvidenceExhibit.of(
                "processed[demo-seed]", "deliberate failure", List.of(foreign, ok, failure));

        assertNotNull(exhibit.okRecord());
        assertEquals("ok", exhibit.okRecord().outcome());
        assertTrue(exhibit.okRecord().durationNanos() >= 0);
        assertEquals("(demo-seed)", exhibit.okRecord().argsSummary());
        assertNotNull(exhibit.exceptionRecord());
        assertEquals("exception:IllegalStateException", exhibit.exceptionRecord().outcome());
        assertEquals(3, exhibit.logTail().size());
    }

    @Test
    void aNullTailIsToleratedAsEmpty() {
        TraceEvidenceExhibit exhibit = TraceEvidenceExhibit.of("r", "m", null);

        assertEquals(List.of(), exhibit.logTail());
        assertNull(exhibit.okRecord());
    }
}
