package guide.domain.routing;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case (topic 07): the route table, optionally filtered by a path prefix.
 * The controller has already turned the query string into plain data; this
 * shell validates the prefix (optional, but must look like a path when
 * present) and delegates filtering and sorting to the exhibit.
 */
public class ListRoutes {

    private final RouteTableSource source;
    private final RouteTableExhibit exhibit;

    public ListRoutes(RouteTableSource source, RouteTableExhibit exhibit) {
        this.source = source;
        this.exhibit = exhibit;
    }

    /**
     * @param prefix optional path prefix ("" or null = the whole table);
     *               when present it must start with "/"
     */
    public Result<RouteTableExhibit.TableView> execute(String prefix) {
        String trimmed = prefix == null ? "" : prefix.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("/")) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "prefix must be an absolute path starting with '/'",
                    Map.of("parameter", "prefix", "received", trimmed)));
        }
        return Result.success(exhibit.view(source.routes(), trimmed));
    }
}
