package guide.domain.authz;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.auth.UserPrincipal;

/**
 * Use case behind the guarded endpoints: perform an action AS a caller,
 * with the authorization decision INSIDE the use case — the layer a URL
 * rule or an annotation cannot reach (callers of this class from a job,
 * a listener, or another use case get the same verdicts the HTTP
 * endpoints get; the policy cannot be bypassed by calling deeper).
 *
 * The flow is the evaluator's flow: unknown document → NOT_FOUND (the
 * taxonomy's routing-shaped answer — with a note that high-security
 * systems merge it into 403 so existence never leaks); deny verdict →
 * FORBIDDEN carrying the checkedBy layer and the reason; allow →
 * perform the action and report the effect.
 *
 * peek() is the deliberate exception: a RAW fetch with no policy at all,
 * existing so the demo's @PostAuthorize specimen route can show
 * check-AFTER-fetch in its purest form (the annotation is that route's
 * ONLY check — see the controller's owner-view). Everything else here
 * denies by default.
 */
public final class ActAsGuardedAction {

    /** The outcome of a guarded action: the verdict trace plus the effect. */
    public record GuardedOutcome(String action, OwnedDocument document, String checkedBy,
            String reason, String effect) {
    }

    private final DocumentStore documentStore;
    private final DenyByDefault evaluator;

    public ActAsGuardedAction(DocumentStore documentStore, DenyByDefault evaluator) {
        this.documentStore = documentStore;
        this.evaluator = evaluator;
    }

    public Result<GuardedOutcome> execute(UserPrincipal caller, String action, String documentId) {
        OwnedDocument document = documentStore.findById(documentId).orElse(null);
        if (document == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "no document '" + documentId + "'",
                    java.util.Map.of("checkedBy", "deny-by-default")));
        }
        DenyByDefault.Verdict verdict = evaluator.decide(caller, action, document);
        if (!verdict.allowed()) {
            return Result.failed(Failure.of(FailureKind.FORBIDDEN, verdict.reason(),
                    java.util.Map.of("checkedBy", verdict.checkedBy())));
        }
        return Result.success(perform(action, document, verdict));
    }

    /**
     * RAW fetch, no policy — the @PostAuthorize specimen's fetch. Named
     * peek() and documented loudly so no future caller mistakes it for a
     * checked read: everything else in this class denies by default.
     */
    public Result<OwnedDocument> peek(String documentId) {
        OwnedDocument document = documentStore.findById(documentId).orElse(null);
        if (document == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "no document '" + documentId + "'",
                    java.util.Map.of("checkedBy", "deny-by-default")));
        }
        return Result.success(document);
    }

    private GuardedOutcome perform(String action, OwnedDocument document,
            DenyByDefault.Verdict verdict) {
        if ("publish".equals(action)
                && document.visibility() != OwnedDocument.Visibility.PUBLIC) {
            OwnedDocument published = document.withVisibility(OwnedDocument.Visibility.PUBLIC);
            documentStore.save(published);
            return new GuardedOutcome(action, published, verdict.checkedBy(), verdict.reason(),
                    "visibility " + document.visibility().wireName() + "→public — now readable by all");
        }
        if ("publish".equals(action)) {
            return new GuardedOutcome(action, document, verdict.checkedBy(), verdict.reason(),
                    "already public — publish is idempotent");
        }
        return new GuardedOutcome(action, document, verdict.checkedBy(), verdict.reason(),
                "read — no mutation");
    }
}
