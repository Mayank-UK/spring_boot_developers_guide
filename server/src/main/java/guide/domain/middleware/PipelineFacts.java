package guide.domain.middleware;

import guide.core.middleware.CompletedTrace;
import java.util.List;

/**
 * The middleware:pipeline demo's response: the request id stamped by the
 * RequestIdFilter, the markers captured for THIS very request up to the
 * handler, the most recent COMPLETED request's full marker sequence from
 * the bounded ring, the servlet filters the running context actually
 * registers (name + order + registration style), and the analysis derived
 * from the current trace.
 */
public record PipelineFacts(
        String requestId,
        List<String> currentRequestTrace,
        CompletedTrace previousCompletedTrace,
        List<RegisteredMiddleware> registeredFilters,
        List<String> analysis) {

    public PipelineFacts {
        currentRequestTrace = currentRequestTrace == null ? List.of() : List.copyOf(currentRequestTrace);
        registeredFilters = registeredFilters == null ? List.of() : List.copyOf(registeredFilters);
        analysis = analysis == null ? List.of() : List.copyOf(analysis);
    }
}
