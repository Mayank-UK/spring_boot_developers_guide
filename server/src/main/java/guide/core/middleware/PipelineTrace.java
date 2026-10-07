package guide.core.middleware;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The per-request marker list the middleware-pipeline demo is built on: one
 * mutable {@code ArrayList<String>} born lazily with the request, stored as a
 * REQUEST ATTRIBUTE, and reached by whoever holds the request — filters,
 * interceptors, and the handler all see the SAME list because they all see
 * the same request.
 *
 * Request attributes, not ThreadLocal, on purpose: an attribute's lifetime is
 * the request's lifetime, so nothing leaks across pooled threads, and the
 * trace survives dispatches (REQUEST, ERROR, ASYNC) that hand the same
 * request object onward. A ThreadLocal would need manual cleanup in every
 * exit path and silently breaks the day an async hand-off moves the rest of
 * a request to another thread.
 */
public final class PipelineTrace {

    /** The attribute key — namespaced so it can never collide with framework attributes. */
    public static final String REQUEST_ATTRIBUTE = "guide.pipelineTrace";

    private PipelineTrace() {
    }

    /** Appends one marker to this request's trace, creating the list on the first mark. */
    public static void mark(HttpServletRequest request, String marker) {
        trace(request).add(Objects.requireNonNull(marker, "marker"));
    }

    /** Immutable snapshot of this request's markers so far; empty when nothing traced yet. */
    public static List<String> markers(HttpServletRequest request) {
        return List.copyOf(trace(request));
    }

    /**
     * The list only this class ever stores under {@link #REQUEST_ATTRIBUTE}
     * (always an {@code ArrayList<String>}), created empty on first touch —
     * which is why the unchecked cast below is locally provable.
     */
    @SuppressWarnings("unchecked")
    private static List<String> trace(HttpServletRequest request) {
        Object attribute = request.getAttribute(REQUEST_ATTRIBUTE);
        if (attribute == null) {
            List<String> fresh = new ArrayList<>();
            request.setAttribute(REQUEST_ATTRIBUTE, fresh);
            return fresh;
        }
        return (List<String>) attribute;
    }
}
