package guide.domain.routing;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Locale;
import java.util.Map;

/**
 * Use case (topic 07): explain which registered mapping would handle a
 * method + path probe. Normalizes the method token to its canonical
 * uppercase form, strips any query string a caller pasted onto the path
 * (input is hostile), validates the basics, and delegates the ranking to
 * the exhibit. Deliberately no failure for "no route matches" — a miss is a
 * VALID answer (the typed no-match verdict), not a failed call.
 */
public class ExplainRouteMatch {

    private final RouteTableSource source;
    private final RouteTableExhibit exhibit;

    public ExplainRouteMatch(RouteTableSource source, RouteTableExhibit exhibit) {
        this.source = source;
        this.exhibit = exhibit;
    }

    public Result<RouteTableExhibit.MatchReport> execute(String method, String path) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        String normalizedPath = path == null ? "" : path.trim();
        int queryAt = normalizedPath.indexOf('?');
        if (queryAt >= 0) {
            normalizedPath = normalizedPath.substring(0, queryAt);
        }
        if (normalizedMethod.isEmpty() || normalizedPath.isEmpty() || !normalizedPath.startsWith("/")) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "method must be non-empty and path must be an absolute path starting with '/'",
                    Map.of("parameter", "method/path")));
        }
        return Result.success(exhibit.explain(source.routes(), normalizedMethod, normalizedPath));
    }
}
