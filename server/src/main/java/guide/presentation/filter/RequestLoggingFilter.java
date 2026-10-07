package guide.presentation.filter;

import guide.core.logging.RequestIdFilter;
import guide.domain.obs.ObsMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per API request, ON COMPLETION — the cheapest always-on
 * observability this app has. For every {@code /api/} path it logs method,
 * path, status and duration, at a level that MEANS something: 2xx/3xx at
 * INFO (a normal beat), 4xx at WARN (a client we could not satisfy — noise
 * worth noticing), 5xx at ERROR (we broke, page someone). The request id
 * and trace id are not in the message because they travel in MDC —
 * the JSON encoder carries them as FIELDS, which is the entire point of
 * structured logging (see {@link #logCompletion} for the one rejoin trick
 * that makes the id survive this filter's finally).
 *
 * The same stopwatch also feeds the demo request timer ({@code guide.demo
 * .requests} on {@link ObsMetrics}) for {@code /api/demo/} paths — one
 * measurement, two outputs (a log line for humans, a meter for graphs).
 *
 * Deliberate omissions, both load-bearing:
 * - NO request/response body capture. Bodies cost memory under load, may
 *   contain payloads and secrets (the security topic's redaction rules
 *   would have to run INSIDE the logging path), and the error envelope
 *   already carries the failure's story. Metadata is safe; bodies are not.
 * - NOT registered for {@code /actuator/**}: health probes fire every few
 *   seconds in every environment — logging them is how "one line per
 *   request" becomes noise that hides the signal.
 *
 * Ordered AFTER the request-id filter (which owns the MDC requestId this
 * line's fields ride on) and after the observation filter (which owns the
 * MDC trace id) — ordering is set where the filter is registered.
 */
public final class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger("guide.http");

    private final ObsMetrics metrics;

    public RequestLoggingFilter(ObsMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startNs = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            String method = request.getMethod();
            String path = request.getRequestURI();
            int status = response.getStatus();
            if (path.startsWith("/api/")) {
                logCompletion(method, path, status, durationMs, response);
                if (path.startsWith("/api/demo/")) {
                    metrics.recordDemoRequest(Duration.ofMillis(durationMs), outcomeFor(status));
                }
            }
        }
    }

    /**
     * Writes the line with the request id REJOINED. This filter logs in its
     * finally — AFTER the inner request-id filter has already cleared the
     * MDC key it owns — so the id is restored from the very response header
     * that filter echoed: the completion line joins the request's story by
     * DATA, not by filter-ordering luck. (The trace id needs no such trick:
     * the observation filter's scope is still open here.)
     */
    private void logCompletion(String method, String path, int status, long durationMs,
            HttpServletResponse response) {
        boolean rejoined = false;
        if (MDC.get(RequestIdFilter.MDC_KEY) == null) {
            String echoed = response.getHeader(RequestIdFilter.HEADER);
            if (echoed != null) {
                MDC.put(RequestIdFilter.MDC_KEY, echoed);
                rejoined = true;
            }
        }
        try {
            LOG.atLevel(levelFor(status))
                    .log("{} {} {} {}ms", method, path, status, durationMs);
        } finally {
            if (rejoined) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        }
    }

    /** The level-by-status contract: 2xx/3xx info, 4xx warn, 5xx error. */
    static Level levelFor(int status) {
        if (status >= 500) {
            return Level.ERROR;
        }
        if (status >= 400) {
            return Level.WARN;
        }
        return Level.INFO;
    }

    /** HTTP status class as the timer's tag — three values ever, so cardinality stays flat. */
    static String outcomeFor(int status) {
        if (status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }
}
