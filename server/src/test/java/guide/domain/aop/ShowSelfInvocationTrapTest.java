package guide.domain.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.aop.InvocationLog;
import guide.core.aop.InvocationRecord;
import guide.core.result.Failed;
import guide.core.result.Success;
import guide.domain.teaching.TracedOrderWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ShowSelfInvocationTrap and SelfInvocationExhibit behavior, derived from
 * whatever the log holds — the report must REFLECT the machinery, never
 * assume it: raw (unproxied) workflow → neither method recorded; a proxied
 * run's log (outer only) → the trap; a both-present log (the fixed world,
 * where the inner step is reached through the proxy) → both recorded.
 */
class ShowSelfInvocationTrapTest {

    /** Unwraps a Success the way the controller does. */
    private static SelfInvocationExhibit run(ShowSelfInvocationTrap useCase) {
        return switch (useCase.execute()) {
            case Success<SelfInvocationExhibit> success -> success.value();
            case Failed<SelfInvocationExhibit> failed -> throw new AssertionError(failed.failure().toString());
        };
    }

    private static InvocationRecord record(String method) {
        return new InvocationRecord(method, "(book)", "ok", 10L, "req-1");
    }

    @Test
    void withoutAProxyNeitherMethodIsRecordedAndTheReportSaysSo() {
        InvocationLog log = new InvocationLog(8);
        ShowSelfInvocationTrap useCase =
                new ShowSelfInvocationTrap(new TracedOrderWorkflow(), log);

        SelfInvocationExhibit exhibit = run(useCase);

        assertEquals("order[book] ← reserved:book", exhibit.orderSummary());
        assertFalse(exhibit.outerRecorded());
        assertFalse(exhibit.innerRecorded());
        assertEquals(List.of(), exhibit.exhibitLog());
    }

    @Test
    void theTrapShapeOuterOnlyIsDerivedFromTheLog() {
        SelfInvocationExhibit exhibit = SelfInvocationExhibit.of("order[book] ← reserved:book",
                List.of(record("guide.domain.teaching.TracedOrderWorkflow.placeOrder")));

        assertTrue(exhibit.outerRecorded(), "outer call reached the proxy and was recorded");
        assertFalse(exhibit.innerRecorded(), "the internal call bypassed the proxy");
        assertEquals("placeOrder", exhibit.outerMethod());
        assertEquals("reserveStock", exhibit.innerMethod());
    }

    @Test
    void theFixedShapeBothRecordedIsDerivedFromTheLogToo() {
        SelfInvocationExhibit exhibit = SelfInvocationExhibit.of("order[book] ← reserved:book",
                List.of(record("guide.domain.teaching.TracedOrderWorkflow.placeOrder"),
                        record("guide.domain.teaching.TracedOrderWorkflow.reserveStock")));

        assertTrue(exhibit.outerRecorded());
        assertTrue(exhibit.innerRecorded(),
                "the control case: reached through the proxy, the inner method IS traced");
    }

    @Test
    void aNullLogIsToleratedAsEmpty() {
        SelfInvocationExhibit exhibit = SelfInvocationExhibit.of("summary", null);

        assertFalse(exhibit.outerRecorded());
        assertFalse(exhibit.innerRecorded());
        assertEquals(List.of(), exhibit.exhibitLog());
    }
}
