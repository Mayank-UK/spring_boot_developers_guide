package guide.presentation;

import guide.core.errors.AppException;
import guide.core.errors.ErrorResponse;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.logging.RequestIdFilter;
import guide.domain.errors.ProblemDetailException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Canonical error-handling advice — the Spring port of the express error
 * middleware. Every failure leaves the app in the same envelope shape:
 *
 *   { error: { code, message, requestId, ...details } }
 *
 * AppExceptions contribute their failure's status/code/message, with the
 * failure's details merged into the envelope; malformed JSON bodies and
 * bodies that failed @Valid validation both map to a 400 validation envelope
 * (the latter carrying a fields map of first-message-per-field); framework
 * misses that implement Spring's ErrorResponse interface (an unrouted path,
 * a wrong method) keep their OWN status in the envelope — 404/405, not the
 * 500 the catch-all used to give them; ProblemDetailException is the RFC
 * 9457 opt-in, rendered as application/problem+json with the same kind,
 * status and log treatment. Anything
 * else becomes a 500 envelope (message generic on the wire, full stack in
 * the log). Nothing is swallowed, and every failure is logged with its
 * request id — 4xx warn (the client's problem), 5xx error (ours).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    ResponseEntity<ErrorResponse> handleAppException(AppException exception, HttpServletRequest request) {
        Failure failure = exception.failure();
        int statusCode = exception.statusCode();
        String requestId = requestId(request);

        if (statusCode >= 500) {
            log.error("request failed: kind={} status={} requestId={}",
                    failure.kind(), statusCode, requestId, exception);
        } else {
            log.warn("request failed: kind={} status={} requestId={} message={}",
                    failure.kind(), statusCode, requestId, failure.message());
        }
        return ResponseEntity.status(statusCode)
                .body(ErrorResponse.of(exception.code(), failure.message(), requestId, failure.details()));
    }

    /** A body that never parsed as JSON is a validation failure, not a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("request failed: kind=validation status=400 requestId={} message=unreadable JSON body",
                requestId);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(guide.core.errors.FailureKind.VALIDATION.errorCode(),
                        "request body is not readable JSON", requestId, null));
    }

    /**
     * A body that parsed but failed @Valid: field errors become the envelope's
     * details — first message per field, in declaration order. Class-level
     * constraint failures are global errors (not FieldErrors), so they shape
     * the count, not the fields map. The log records field NAMES only:
     * messages and rejected values stay off the log line (one of them may be
     * a password).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        String requestId = requestId(request);
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        log.warn("request failed: kind=validation status=400 requestId={} fields={}",
                requestId, fieldErrors.keySet());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(FailureKind.VALIDATION.errorCode(),
                        "request body failed validation", requestId, Map.of("fields", fieldErrors)));
    }

    /**
     * Framework misses that carry their own answer: Spring exceptions
     * implementing the ErrorResponse interface arrive with status and
     * problem-details body ALREADY chosen (the routing topic's verified
     * examples: NoResourceFoundException for an unrouted path,
     * HttpRequestMethodNotSupportedException for a wrong method; the
     * media-type and request-binding families behave the same). Before this
     * branch they fell through to the Exception catch-all below and
     * rendered as 500s; now their status survives into OUR envelope — 404,
     * 405, whatever the framework chose — with the code derived per status.
     *
     * The annotation itself cannot name the interface: @ExceptionHandler
     * values must be Classes extending Throwable, and ErrorResponse is an
     * interface — so the branch lists the Throwable FAMILIES Spring's own
     * misses live under (every listed type implements the interface; the
     * body treats them as it). MethodArgumentNotValidException also
     * implements the interface but never reaches this branch: the resolver
     * prefers the MORE SPECIFIC handler above. And two types named
     * ErrorResponse meet in this file — our envelope record and Spring's
     * interface — so the framework one is spelled fully qualified.
     */
    @ExceptionHandler({NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeException.class,
            ServletRequestBindingException.class})
    ResponseEntity<ErrorResponse> handleFrameworkErrorResponse(ServletException exception,
            HttpServletRequest request) {
        org.springframework.web.ErrorResponse framework =
                (org.springframework.web.ErrorResponse) exception;
        int statusCode = framework.getStatusCode().value();
        ProblemDetail body = framework.getBody();
        String message = body.getDetail() != null ? body.getDetail()
                : body.getTitle() != null ? body.getTitle()
                        : exception.getClass().getSimpleName();
        String requestId = requestId(request);
        if (statusCode >= 500) {
            log.error("request failed: framework={} status={} requestId={}",
                    exception.getClass().getSimpleName(), statusCode, requestId, exception);
        } else {
            log.warn("request failed: framework={} status={} requestId={} message={}",
                    exception.getClass().getSimpleName(), statusCode, requestId, message);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (body.getTitle() != null) {
            details.put("title", body.getTitle());
        }
        if (body.getType() != null) {
            details.put("type", body.getType().toString());
        }
        return ResponseEntity.status(statusCode)
                .headers(framework.getHeaders())
                .body(ErrorResponse.of(envelopeCodeFor(statusCode), message, requestId, details));
    }

    /**
     * The RFC 9457 rendition — the opt-in second representation. A route
     * that fails with ProblemDetailException renders the SAME typed failure
     * as application/problem+json instead of the app envelope: identical
     * kind, status, message and log treatment; only the wire shape differs.
     * The demo exposes one failure through both contracts (/api/demo/errors
     * /inject vs /problem-detail) so the choice is comparable live.
     */
    @ExceptionHandler(ProblemDetailException.class)
    ResponseEntity<ProblemDetail> handleProblemDetailRendition(ProblemDetailException exception,
            HttpServletRequest request) {
        Failure failure = exception.failure();
        int statusCode = exception.statusCode();
        String requestId = requestId(request);
        if (statusCode >= 500) {
            log.error("request failed: kind={} status={} requestId={} rendition=problem-detail",
                    failure.kind(), statusCode, requestId, exception);
        } else {
            log.warn("request failed: kind={} status={} requestId={} message={} rendition=problem-detail",
                    failure.kind(), statusCode, requestId, failure.message());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(statusCode), failure.message());
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status != null) {
            problem.setTitle(status.getReasonPhrase());
        }
        problem.setType(URI.create("https://errors.springboot-developers-guide.dev/"
                + exception.code()));
        problem.setProperty("code", exception.code());
        problem.setProperty("requestId", requestId);
        if (failure.details() != null) {
            failure.details().forEach(problem::setProperty);
        }
        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * A Spring DataAccessException that reached the edge UNclassified — the
     * signature of a backing store that is down under a live-routed
     * scenario. The optional-service adapters translate the family at their
     * own port's edge (the caching router's guarded() is the pattern); this
     * branch is the net for the paths without such a router — a JdbcTemplate
     * call against an absent PostgreSQL, for instance — so the degrade-
     * gracefully rule holds end-to-end: a typed 503 service_unavailable
     * envelope, never the generic 500 the catch-all below would render.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException exception,
            HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("request failed: kind={} status={} requestId={}",
                FailureKind.UNAVAILABLE, FailureKind.UNAVAILABLE.httpStatus(), requestId, exception);
        return ResponseEntity.status(FailureKind.UNAVAILABLE.httpStatus())
                .body(ErrorResponse.of(FailureKind.UNAVAILABLE.errorCode(),
                        "A backing store is unreachable — the service is degraded until it returns",
                        requestId, null));
    }

    /** The safety net: unknown faults get the same envelope, generic message, full log. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnknown(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("unhandled request error: requestId={}", requestId, exception);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(guide.core.errors.FailureKind.INTERNAL.errorCode(),
                        "Internal server error", requestId, null));
    }

    /**
     * Framework status → envelope code: the exact taxonomy match when one
     * exists (404 not_found, 503 service_unavailable), else the status
     * reason phrase snake_cased (405 method_not_allowed) — so the envelope
     * stays the one contract even for statuses the taxonomy itself never
     * emits.
     */
    private static String envelopeCodeFor(int statusCode) {
        return Arrays.stream(FailureKind.values())
                .filter(kind -> kind.httpStatus() == statusCode)
                .findFirst()
                .map(FailureKind::errorCode)
                .orElseGet(() -> {
                    HttpStatus status = HttpStatus.resolve(statusCode);
                    return status != null
                            ? status.getReasonPhrase().toLowerCase(Locale.ROOT).replace(' ', '_')
                            : "http_" + statusCode;
                });
    }

    /**
     * The request id the RequestIdFilter stamped: request attribute first
     * (survives everything), MDC second, "n/a" when no filter ran (slice
     * tests) — same fallback the express middleware uses.
     */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isEmpty()) {
            return value;
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }
}
