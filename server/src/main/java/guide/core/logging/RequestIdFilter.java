package guide.core.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with a correlation id before anything else runs, so
 * both success responses and the error envelope can echo it. An incoming
 * well-formed {@code x-request-id} is honored; anything absent, blank, or
 * unsafe (header-injection characters) is replaced by a generated UUID.
 *
 * The id lands in three places: the response header (echo), the request
 * attribute (robust read from the advice), and the MDC (log correlation).
 * MDC is always cleared in a finally block — even when the chain throws.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-request-id";
    public static final String MDC_KEY = "requestId";
    public static final String REQUEST_ATTRIBUTE = "requestId";

    /** Defensive ceiling on honored incoming ids; anything longer is treated as unsafe. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !SAFE_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
