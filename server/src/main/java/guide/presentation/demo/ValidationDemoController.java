package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.validation.ArenaSubmission;
import guide.domain.validation.AssessArenaSubmission;
import guide.domain.validation.ValidationArena;
import guide.core.logging.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 10 (validation) — one payload, two philosophies:
 * - POST /api/demo/validation/arena       → the ANALYST: binds the body WITHOUT
 *   @Valid and hands it to the arena exhibit, which runs the same Bean
 *   Validation machinery programmatically and answers 200 with a per-field
 *   verdict — invalid input is the demo's product, not its error — plus the
 *   400 envelope a strict endpoint would have emitted for the same payload
 * - POST /api/demo/validation/arena/strict → the PRODUCTION default: @Valid on
 *   the @RequestBody, so a violation throws MethodArgumentNotValidException
 *   and the global handler answers the standard 400 envelope
 *
 * Same DTO, same constraints, same validator: only WHO drives validation
 * changes. That pair is the topic's "choosing between them" argument, live.
 */
@RestController
public class ValidationDemoController {

    private final AssessArenaSubmission assessArenaSubmission;

    public ValidationDemoController(AssessArenaSubmission assessArenaSubmission) {
        this.assessArenaSubmission = assessArenaSubmission;
    }

    /** The analyst: never rejects — judges. Returns the verdict + envelope preview. */
    @PostMapping(value = "/api/demo/validation/arena",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ValidationArena.ArenaVerdict arena(@RequestBody ArenaSubmission submission,
            HttpServletRequest request) {
        return unwrap(assessArenaSubmission.execute(submission, requestId(request)));
    }

    /** The production default: @Valid ⇒ violations become the standard 400 envelope. */
    @PostMapping(value = "/api/demo/validation/arena/strict",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ValidationArena.ArenaVerdict strict(@Valid @RequestBody ArenaSubmission submission,
            HttpServletRequest request) {
        return unwrap(assessArenaSubmission.execute(submission, requestId(request)));
    }

    /** The request id the filter stamped — same fallback chain as the advice's. */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isEmpty()) {
            return value;
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
