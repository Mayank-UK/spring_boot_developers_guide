package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.logging.RequestIdFilter;
import guide.core.middleware.PipelineTrace;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.middleware.PipelineFacts;
import guide.domain.middleware.ShowPipelineTrace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 08 (filters-interceptors) — thin like every
 * controller here. GET /api/demo/middleware/pipeline marks the "you are
 * here" handler marker on the request's trace (presentation-layer
 * instrumentation: the handler is the only code that knows it is executing),
 * reads the markers the real middleware recorded for THIS very request, and
 * hands them to the use case, which pairs them with the previous completed
 * trace from the bounded ring and the running app's registered filters.
 */
@RestController
public class MiddlewareDemoController {

    private final ShowPipelineTrace showPipelineTrace;

    public MiddlewareDemoController(ShowPipelineTrace showPipelineTrace) {
        this.showPipelineTrace = showPipelineTrace;
    }

    /** GET /api/demo/middleware/pipeline → 200 this request's trace + the previous completed one. */
    @GetMapping("/api/demo/middleware/pipeline")
    PipelineFacts pipeline(HttpServletRequest request) {
        PipelineTrace.mark(request, ShowPipelineTrace.HANDLER_MARKER);
        return unwrap(showPipelineTrace.execute(requestId(request), PipelineTrace.markers(request)));
    }

    /** The id RequestIdFilter stamped into the request attribute; "n/a" when no filter ran (slices). */
    private static String requestId(HttpServletRequest request) {
        return request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE) instanceof String value
                ? value
                : "n/a";
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
