package guide.core.middleware;

import java.util.List;

/**
 * One fully unwound request: the request id RequestIdFilter stamped, the
 * method/path/status the servlet saw, and the COMPLETE marker sequence from
 * first filter entry to last filter exit — filed by the outermost middleware
 * filter's finally block, the last of this topic's code a request executes.
 */
public record CompletedTrace(
        String requestId,
        String method,
        String path,
        int status,
        List<String> markers) {

    public CompletedTrace {
        markers = markers == null ? List.of() : List.copyOf(markers);
    }
}
