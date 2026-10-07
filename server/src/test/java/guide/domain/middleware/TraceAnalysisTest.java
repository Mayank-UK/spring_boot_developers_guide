package guide.domain.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.middleware.MiddlewareOrderingFilter;
import guide.core.middleware.PipelineTraceFilter;
import guide.core.middleware.PipelineTraceInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TraceAnalysis contract: the findings derive from the markers the
 * machinery actually recorded — the demo sequence proves the wrap, a chain
 * without filters is diagnosed honestly, and a completed sequence explains
 * its own unwind.
 */
class TraceAnalysisTest {

    @Test
    void theDemoSequenceProvesTheWrapLayerByLayer() {
        List<String> findings = TraceAnalysis.of(List.of(
                MiddlewareOrderingFilter.BEFORE_MARKER,
                PipelineTraceFilter.BEFORE_MARKER,
                PipelineTraceInterceptor.PRE_HANDLE_MARKER,
                ShowPipelineTrace.HANDLER_MARKER));

        assertTrue(findings.stream().anyMatch(f -> f.contains("LOWER order value")),
                "ordering finding expected: " + findings);
        assertTrue(findings.stream().anyMatch(f -> f.contains("wraps the DispatcherServlet")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("wraps the mapped handler")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("stops at the handler")),
                "the in-flight trace must explain its own cutoff");
    }

    @Test
    void aFilterlessTraceIsDiagnosedNotCrashed() {
        List<String> findings = TraceAnalysis.of(List.of(
                PipelineTraceInterceptor.PRE_HANDLE_MARKER,
                ShowPipelineTrace.HANDLER_MARKER));

        assertTrue(findings.stream().anyMatch(f -> f.contains("MiddlewareOrderingFilter never saw")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("PipelineTraceFilter never saw")));
        assertFalse(findings.stream().anyMatch(f -> f.contains("LOWER order value")),
                "no ordering claim without both before-markers");
    }

    @Test
    void anEmptyTraceSaysSoHonestly() {
        List<String> findings = TraceAnalysis.of(List.of());

        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().contains("no markers captured"));
    }

    @Test
    void aCompletedTraceExplainsItsOwnUnwind() {
        List<String> findings = TraceAnalysis.of(List.of(
                MiddlewareOrderingFilter.BEFORE_MARKER,
                PipelineTraceFilter.BEFORE_MARKER,
                PipelineTraceInterceptor.PRE_HANDLE_MARKER,
                ShowPipelineTrace.HANDLER_MARKER,
                PipelineTraceInterceptor.POST_HANDLE_MARKER,
                PipelineTraceInterceptor.AFTER_COMPLETION_MARKER,
                PipelineTraceFilter.AFTER_MARKER,
                MiddlewareOrderingFilter.AFTER_MARKER));

        assertTrue(findings.stream().anyMatch(f -> f.contains("the unwind is present")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("INSIDE the filter chain")));
    }

    @Test
    void reversedBeforeMarkersReportTheOrderingAsBroken() {
        List<String> findings = TraceAnalysis.of(List.of(
                PipelineTraceFilter.BEFORE_MARKER,
                MiddlewareOrderingFilter.BEFORE_MARKER,
                PipelineTraceInterceptor.PRE_HANDLE_MARKER,
                ShowPipelineTrace.HANDLER_MARKER));

        assertTrue(findings.stream().anyMatch(f -> f.contains("did not apply the declared order")));
    }
}
