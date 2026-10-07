package guide.domain.mongo;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: record one page view. Input judged HERE, at the boundary, as
 * typed Failure values (the topic-10 discipline): a malformed topic id,
 * path, duration, or locale answers 400 with the field named — never an
 * exception, never a silent coercion. A valid draft goes to the port; if
 * the live adapter is active and MongoDB is unreachable, the ADAPTER's
 * typed unavailable exception bubbles to the controller advice (503
 * envelope) — use cases translate domain rules, adapters translate
 * infrastructure states, and neither does the other's job.
 */
public class RecordVisit {

    private final VisitStore visitStore;

    public RecordVisit(VisitStore visitStore) {
        this.visitStore = visitStore;
    }

    public Result<VisitDocument> execute(VisitDraft draft) {
        if (draft == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "a visit requires topicId, path, visitorToken, durationMs and locale",
                    Map.of("parameter", "body")));
        }
        try {
            return Result.success(visitStore.record(draft));
        } catch (IllegalArgumentException rejected) {
            // The records' compact constructors are the last wall; their
            // message is already field-shaped for the envelope.
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    rejected.getMessage(), Map.of("parameter", "visit")));
        }
    }
}
