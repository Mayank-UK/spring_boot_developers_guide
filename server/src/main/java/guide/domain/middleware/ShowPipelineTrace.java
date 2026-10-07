package guide.domain.middleware;

import guide.core.middleware.PipelineTraceLog;
import guide.core.result.Result;
import java.util.List;

/**
 * Use case behind GET /api/demo/middleware/pipeline: assemble what the demo
 * needs from three real sources — the markers the middleware machinery
 * recorded for THIS request (passed in by the controller, which holds the
 * request), the most recent completed trace from the bounded ring, and the
 * servlet filters the running context actually registers. The analysis is
 * derived pure, from whatever the markers really say.
 *
 * This use case is deliberately demo-shaped: it reads evidence and derives
 * a report; it never mutates the ring (only the outermost filter files
 * completed traces) and never touches Spring.
 */
public class ShowPipelineTrace {

    /** Appended by the demo controller from inside the handler method — the "you are here" marker. */
    public static final String HANDLER_MARKER = "handler:middleware-pipeline";

    private final PipelineTraceLog traceLog;
    private final MiddlewareRegistrationSource registrationSource;

    public ShowPipelineTrace(PipelineTraceLog traceLog, MiddlewareRegistrationSource registrationSource) {
        this.traceLog = traceLog;
        this.registrationSource = registrationSource;
    }

    public Result<PipelineFacts> execute(String requestId, List<String> currentMarkers) {
        return Result.success(new PipelineFacts(
                requestId,
                currentMarkers,
                traceLog.latest(),
                registrationSource.servletFilters(),
                TraceAnalysis.of(currentMarkers)));
    }
}
