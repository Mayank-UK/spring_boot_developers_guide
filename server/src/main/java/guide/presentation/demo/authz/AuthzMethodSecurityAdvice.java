package guide.presentation.demo.authz;

import guide.core.errors.ErrorResponse;
import guide.core.errors.FailureKind;
import guide.core.logging.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The method-security refusal, translated into the app's ONE error
 * envelope. When @PreAuthorize/@PostAuthorize denies, method security
 * throws AccessDeniedException from inside the handler — DispatcherServlet
 * offers it to the advice chain BEFORE the security filter chain's
 * ExceptionTranslationFilter ever sees it, so this handler decides its
 * wire shape. Without it the generic fallback would render a 500
 * envelope (an authz denial is not a server fault); with it the demo's
 * 403s are the same {error:{code,message,requestId}} every other
 * failure uses — the taxonomy lesson, applied to Spring Security's own
 * exception type.
 *
 * Ordered ABOVE the global advice so this specific handler wins for
 * this specific exception; everything else still falls through.
 * (Chain-level denials — the URL rules — never reach advices; the
 * ExceptionTranslationFilter answers those before the servlet runs.)
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthzMethodSecurityAdvice {

    private static final Logger log = LoggerFactory.getLogger(AuthzMethodSecurityAdvice.class);

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception,
            HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("request denied: kind=forbidden status=403 requestId={} layer=method-security",
                requestId);
        return ResponseEntity.status(FailureKind.FORBIDDEN.httpStatus())
                .body(ErrorResponse.of(FailureKind.FORBIDDEN.errorCode(),
                        "access denied by method security — the @PreAuthorize/@PostAuthorize "
                                + "verdict was deny",
                        requestId, null));
    }

    /** Same request-id resolution the global advice uses (attribute, MDC, n/a). */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isEmpty()) {
            return value;
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }
}
