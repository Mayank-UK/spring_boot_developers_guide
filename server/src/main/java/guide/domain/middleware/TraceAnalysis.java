package guide.domain.middleware;

import guide.core.middleware.MiddlewareOrderingFilter;
import guide.core.middleware.PipelineTraceFilter;
import guide.core.middleware.PipelineTraceInterceptor;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure exhibit logic: read a request's marker sequence and say what it
 * PROVES — which layer wrapped which, and what any missing marker would
 * mean. No Spring types, no servlet types: a list of strings in, findings
 * out, so the pipeline demo's analysis is testable (and honest — it derives
 * from whatever the machinery actually recorded, never from assumptions).
 */
public final class TraceAnalysis {

    private TraceAnalysis() {
    }

    /** Findings for one captured marker sequence — the demo's "what am I looking at" layer. */
    public static List<String> of(List<String> markers) {
        List<String> findings = new ArrayList<>();
        if (markers.isEmpty()) {
            findings.add("no markers captured — neither the servlet filters nor the interceptor ran for this "
                    + "request (a MockMvc slice without filters, or a non-servlet dispatch); this response's "
                    + "trace evidence cannot exist.");
            return List.copyOf(findings);
        }

        int orderingBefore = markers.indexOf(MiddlewareOrderingFilter.BEFORE_MARKER);
        int traceBefore = markers.indexOf(PipelineTraceFilter.BEFORE_MARKER);
        int preHandle = markers.indexOf(PipelineTraceInterceptor.PRE_HANDLE_MARKER);
        int handler = markers.indexOf(ShowPipelineTrace.HANDLER_MARKER);

        if (orderingBefore >= 0 && traceBefore >= 0) {
            findings.add(orderingBefore < traceBefore
                    ? "filter:ordering:before precedes filter:trace:before — @Order(20) ran before @Order(30): "
                            + "the LOWER order value enters the chain earlier, and the unwind mirrors it (the "
                            + "@Order(30) filter's after-marker precedes the @Order(20)'s)."
                    : "the @Order(20) marker follows the @Order(30) one — the chain did not apply the declared "
                            + "order (a custom registration or an ordering override is in play).");
        }
        if (traceBefore >= 0 && preHandle >= 0 && preHandle > traceBefore) {
            findings.add("filter:trace:before precedes interceptor:preHandle — the servlet filter chain wraps "
                    + "the DispatcherServlet: filters see raw servlet requests before any handler is mapped.");
        }
        if (preHandle >= 0 && handler >= 0 && handler > preHandle) {
            findings.add("interceptor:preHandle precedes the handler marker — the interceptor wraps the mapped "
                    + "handler method; the handler then appended its own marker from inside the method you are "
                    + "reading.");
        }

        if (orderingBefore < 0) {
            findings.add("filter:ordering:before missing — the @Order(20) MiddlewareOrderingFilter never saw "
                    + "this request: the servlet filter chain did not run (a test slice), or its registration "
                    + "was removed.");
        }
        if (traceBefore < 0) {
            findings.add("filter:trace:before missing — the @Order(30) PipelineTraceFilter never saw this "
                    + "request: same diagnosis, the filter chain was absent for this dispatch.");
        }
        if (preHandle < 0) {
            findings.add("interceptor:preHandle missing — no mapped-handler interception: the interceptor is "
                    + "not registered for this path, or no handler mapping selected a handler for the request.");
        }
        if (handler < 0) {
            findings.add("the handler marker missing — this trace was not captured inside the pipeline demo's "
                    + "handler: the chain ran for some other endpoint.");
        }

        boolean unwindPresent = markers.indexOf(PipelineTraceInterceptor.POST_HANDLE_MARKER) >= 0
                || markers.indexOf(PipelineTraceInterceptor.AFTER_COMPLETION_MARKER) >= 0
                || markers.indexOf(PipelineTraceFilter.AFTER_MARKER) >= 0;
        findings.add(unwindPresent
                ? "the unwind is present — postHandle, afterCompletion and the filter after-markers follow the "
                        + "handler marker: afterCompletion runs INSIDE the filter chain, so both filter "
                        + "after-markers land after it."
                : "this trace stops at the handler on purpose: postHandle, afterCompletion and both filter "
                        + "after-markers run AFTER the response is serialized, so they cannot appear in the "
                        + "very response they trail — the previous request's completed trace shows the full "
                        + "unwind.");
        return List.copyOf(findings);
    }
}
