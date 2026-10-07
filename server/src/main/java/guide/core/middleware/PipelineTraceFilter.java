package guide.core.middleware;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Topic 08's teaching filter: the smallest honest proof that a servlet
 * filter WRAPS the rest of the chain. One marker before the chain continues,
 * one marker after it returns — the pair brackets everything downstream
 * (every later filter, the DispatcherServlet, the interceptor, the handler,
 * the advice) and is visible in the per-request trace the pipeline demo
 * serves. Registered explicitly by the composition root at order 30 —
 * AFTER the request-id stamping and the ordering marker have run, so its
 * "before" marker lands after theirs in the trace.
 *
 * Always on, deliberately cheap: two string appends into a request-attribute
 * list, no I/O, nothing allocated per mark beyond the string itself. Real
 * middleware that runs on EVERY request must be priced like real middleware.
 */
public class PipelineTraceFilter extends OncePerRequestFilter {

    public static final String BEFORE_MARKER = "filter:trace:before";
    public static final String AFTER_MARKER = "filter:trace:after";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PipelineTrace.mark(request, BEFORE_MARKER);
        try {
            chain.doFilter(request, response);
        } finally {
            PipelineTrace.mark(request, AFTER_MARKER);
        }
    }
}
