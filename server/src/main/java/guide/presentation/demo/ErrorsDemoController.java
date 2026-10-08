package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.FailureKind;
import guide.core.errors.ValidationException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.errors.InjectFailure;
import guide.domain.errors.ProblemDetailException;
import guide.domain.errors.ReadFailureCatalog;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 30 (exception handling) — the failure explorer:
 * - GET  /api/demo/errors/catalog         → the taxonomy as data: the eight
 *   FailureKind rows plus the two framework ErrorResponse rows (404/405),
 *   each with status, envelope code, when-it-fires and the exact example
 *   message /inject renders for that kind
 * - POST /api/demo/errors/inject          → GENUINELY FAILS: the use case
 *   returns a Failed Result, the edge converts it to the typed exception
 *   (AppException.of), and the global advice renders the envelope — so the
 *   response you get IS the real thing: the kind's own status + envelope
 *   ({kind, includeDetails}; details are the kind's canonical example
 *   details, INTERNAL always stays detail-free — leak discipline)
 * - GET  /api/demo/errors/problem-detail  → the SAME failure through the
 *   RFC 9457 contract: the edge throws ProblemDetailException and the
 *   advice renders application/problem+json instead of the envelope
 *   (?kind=&includeDetails=)
 *
 * Thin as always: bind, call ONE use case, map the Result onto HTTP — the
 * rendering of failures belongs to the advice, never to a controller.
 */
@RestController
public class ErrorsDemoController {

    private final ReadFailureCatalog readFailureCatalog;
    private final InjectFailure injectFailure;

    public ErrorsDemoController(ReadFailureCatalog readFailureCatalog, InjectFailure injectFailure) {
        this.readFailureCatalog = readFailureCatalog;
        this.injectFailure = injectFailure;
    }

    @GetMapping(value = "/api/demo/errors/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ReadFailureCatalog.Row> catalog() {
        return readFailureCatalog.execute();
    }

    public record InjectRequest(String kind, Boolean includeDetails) {
    }

    /** Always renders a failure envelope — that is the feature, not a bug. */
    @PostMapping(value = "/api/demo/errors/inject",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Void inject(@RequestBody(required = false) InjectRequest request) {
        FailureKind kind = parseKind(request == null ? null : request.kind());
        boolean includeDetails = request == null || request.includeDetails() == null
                || request.includeDetails();
        return unwrap(injectFailure.execute(kind, includeDetails));
    }

    /** The same failure, the standard shape: RFC 9457 problem+json (see the advice branch). */
    @GetMapping(value = "/api/demo/errors/problem-detail",
            produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    Void problemDetail(@RequestParam String kind,
            @RequestParam(defaultValue = "false") boolean includeDetails) {
        return switch (injectFailure.execute(parseKind(kind), includeDetails)) {
            case Success<Void> ignored -> null;
            case Failed<Void> failed -> throw new ProblemDetailException(failed.failure());
        };
    }

    /** Unknown or absent kind is itself a typed validation failure — the taxonomy policing its own door. */
    private static FailureKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("kind is required — GET /api/demo/errors/catalog lists the eight",
                    java.util.Map.of("field", "kind"));
        }
        try {
            return FailureKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAKnownKind) {
            throw new ValidationException("unknown failure kind \"" + raw.trim() + "\"",
                    java.util.Map.of("field", "kind", "validKinds",
                            java.util.Arrays.toString(FailureKind.values())));
        }
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
