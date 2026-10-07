package guide.domain.errors;

import guide.core.errors.AppException;
import guide.core.errors.Failure;

/**
 * The RFC 9457 opt-in marker: an AppException whose answer the global advice
 * renders as application/problem+json instead of the app envelope (see the
 * ProblemDetailException branch in GlobalExceptionHandler). Carrying the
 * marker is an EDGE decision — the failure, its kind, its status and its
 * log treatment are identical to the envelope rendition; only the wire
 * shape differs. The demo exposes the same failure through both contracts
 * (/api/demo/errors/inject vs /api/demo/errors/problem-detail) so the two
 * representations can be compared live.
 */
public class ProblemDetailException extends AppException {

    public ProblemDetailException(Failure failure) {
        super(failure);
    }
}
