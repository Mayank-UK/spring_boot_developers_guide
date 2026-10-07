package guide.core.middleware;

import guide.core.logging.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The outermost of this topic's two filters, ordered 20 so the demo can SHOW
 * ordering: lower order value runs earlier on the way in and later on the
 * way out, which is why this filter's "before" marker is first in every
 * trace and its "after" marker is last. Being outermost gives it a second
 * job no inner component can do: its finally block is the last of this
 * topic's code a request executes, so THAT is where the fully unwound marker
 * sequence — filters, interceptor, handler, advice, all the exit markers —
 * is filed into the bounded ring of completed traces.
 *
 * The class carries @Order(20) and is registered as a plain @Bean (the
 * sibling PipelineTraceFilter uses the other style, an explicit
 * FilterRegistrationBean) so the demo's filter introspection can show both
 * registration styles with their real orders.
 */
@Order(20)
public class MiddlewareOrderingFilter extends OncePerRequestFilter {

    public static final String BEFORE_MARKER = "filter:ordering:before";
    public static final String AFTER_MARKER = "filter:ordering:after";

    private static final Logger log = LoggerFactory.getLogger(MiddlewareOrderingFilter.class);

    private final PipelineTraceLog traceLog;

    public MiddlewareOrderingFilter(PipelineTraceLog traceLog) {
        this.traceLog = traceLog;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PipelineTrace.mark(request, BEFORE_MARKER);
        try {
            chain.doFilter(request, response);
        } finally {
            PipelineTrace.mark(request, AFTER_MARKER);
            fileCompletedTrace(request, response);
        }
    }

    /**
     * Snapshots the completed trace into the ring. Instrumentation must
     * never take an already-written response down with it, so a filing
     * failure is logged and the request ends normally — the response left
     * this filter's control long before this line.
     */
    private void fileCompletedTrace(HttpServletRequest request, HttpServletResponse response) {
        Object stamped = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        String requestId = stamped instanceof String value ? value : null;
        try {
            traceLog.record(new CompletedTrace(requestId, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), PipelineTrace.markers(request)));
        } catch (RuntimeException instrumentationFailure) {
            log.debug("pipeline trace filing skipped: {}", instrumentationFailure.getMessage());
        }
    }
}
