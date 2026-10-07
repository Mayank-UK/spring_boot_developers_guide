package guide.domain.errors;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Use case behind GET /api/demo/errors/catalog (topic 30): one row per way
 * this app can fail. The eight typed rows are DERIVED from {@link FailureKind}
 * plus {@link InjectFailure}'s descriptors (so the example message column is
 * exactly what injecting that kind renders), with the typed exception class
 * computed by asking {@link AppException#of} — the same factory the edge
 * uses, so the "raisedBy" column cannot drift from the real throw.
 *
 * Two more rows describe the FRAMEWORK family: exceptions Spring itself
 * raises for routing misses, which implement Spring's ErrorResponse
 * interface and (since this topic added the advice branch) render through
 * the same envelope with their own status — 404 for an unrouted path, 405
 * for a wrong method. Those cannot be injected as values (the framework
 * raises them before any handler runs); the reproduce column says how to
 * trigger them for real.
 */
public final class ReadFailureCatalog {

    /** One catalog row: the taxonomy line the explorer renders. */
    public record Row(String kind, String raisedBy, int httpStatus, String envelopeCode,
            String whenItFires, String exampleMessage, boolean injectable, String reproduce) {
    }

    private final InjectFailure injectFailure;

    public ReadFailureCatalog(InjectFailure injectFailure) {
        this.injectFailure = injectFailure;
    }

    public List<Row> execute() {
        List<Row> rows = new ArrayList<>();
        for (FailureKind kind : FailureKind.values()) {
            Failure example = injectFailure.failureOf(kind, true);
            rows.add(new Row(kind.name(), typedExceptionName(example), kind.httpStatus(),
                    kind.errorCode(), injectFailure.descriptorFor(kind).whenItFires(),
                    example.message(), true,
                    "POST /api/demo/errors/inject {\"kind\":\"" + kind.name() + "\"}"));
        }
        rows.add(new Row("NoResourceFoundException",
                "framework — the /** static-resource handler (implements ErrorResponse)",
                404, "not_found",
                "an UNROUTED path: no controller pattern matched, the resource handler found no file",
                "No static resource api/definitely/not/routed.",
                false, "curl -i localhost:8080/api/definitely/not/routed"));
        rows.add(new Row("HttpRequestMethodNotSupportedException",
                "framework — the DispatcherServlet (implements ErrorResponse)",
                405, "method_not_allowed",
                "a ROUTED path hit with a method none of its mappings declare",
                "Method 'POST' is not supported.",
                false, "curl -i -X POST localhost:8080/api/demo/errors/catalog"));
        return List.copyOf(rows);
    }

    /** The exception class AppException.of would build for this failure — the real raisedBy. */
    private static String typedExceptionName(Failure example) {
        return AppException.of(example).getClass().getSimpleName();
    }
}
