package guide.domain.http;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Use case: annotate one HTTP request for topic 03's inspector demo. The
 * controller has already turned the servlet request into plain data (the
 * dependency rule stops Spring at the edge); this use case is the policy
 * shell — validate the basics, normalize the method token to its canonical
 * uppercase form, delegate classification to the exhibit, and return the
 * report as a Success. There is deliberately no "unknown query" or "unknown
 * header" failure: an inspector's whole job is to tolerate and report
 * whatever the wire carried, not to police it.
 */
public class InspectHttpRequest {

    private final HeadersExhibit exhibit;

    public InspectHttpRequest(HeadersExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<HeadersExhibit.InspectReport> execute(String method, String path, String rawQuery,
            List<HeadersExhibit.IncomingHeader> headers, HeadersExhibit.IncomingBody body) {
        if (method == null || method.isBlank() || path == null || path.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "method and path must be non-empty strings",
                    Map.of("parameter", "method/path")));
        }
        return Result.success(exhibit.inspect(
                method.trim().toUpperCase(Locale.ROOT), path.trim(), rawQuery, headers, body));
    }
}
