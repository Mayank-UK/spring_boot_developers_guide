package guide.domain.sqldemo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import java.util.UUID;

/**
 * Topic 17's audit use case (demo db:audit): validate the request the way
 * the posts substrate already taught (a title is 1..120 chars after trim; a
 * post id is a UUID — a hostile id is a validation failure, never driver
 * SQL), run the transactional transfer, and — the whole point — build the
 * PROOF from committed truth read AFTER the transaction ended. The failAfter
 * flag asks the store to crash between the post UPDATE and the audit INSERT;
 * the rolled-back proof then shows the two numbers that cannot lie: the
 * title is unchanged AND the trail count is unchanged.
 */
public final class AuditPostTransfer {

    /** PostDraft.TITLE_MAX, restated so the demo's contract matches the substrate's. */
    private static final int TITLE_MAX = 120;

    private static final String CRASH_MARKER = "simulated crash";

    private final AuditTrailStore store;

    public AuditPostTransfer(AuditTrailStore store) {
        this.store = store;
    }

    /** The proof object for one audited title transfer (committed or rolled back). */
    public TransferProof transfer(String postId, String title, String changedBy, boolean failAfter) {
        UUID id = requirePostId(postId);
        String newTitle = requireTitle(title);
        String actor = requireActor(changedBy);

        String titleBefore = store.titleOf(id.toString())
                .orElseThrow(() -> AppException.of(Failure.of(FailureKind.NOT_FOUND,
                        "no post with id " + id + " — transfer against the posts substrate's seed ids works out of the box")));
        int trailBefore = store.trailCount(id.toString());
        try {
            store.transferAndAudit(id.toString(), newTitle, actor, failAfter);
        } catch (IllegalStateException crash) {
            // The crash is BETWEEN the writes; by the time we land here the
            // transaction has rolled back — read committed truth as the proof.
            String titleAfter = store.titleOf(id.toString()).orElse(null);
            int trailAfter = store.trailCount(id.toString());
            return new TransferProof(false, id.toString(), titleBefore, titleAfter, actor,
                    trailBefore, trailAfter,
                    "rolled back — the crash (" + crash.getMessage().replace(CRASH_MARKER + " ", "")
                            + ") left NO trace: title unchanged, trail row never existed");
        }
        String titleAfter = store.titleOf(id.toString()).orElseThrow();
        int trailAfter = store.trailCount(id.toString());
        return new TransferProof(true, id.toString(), titleBefore, titleAfter, actor,
                trailBefore, trailAfter,
                "one commit, both facts: the new title and its trail row landed together or not at all");
    }

    /**
     * What one transfer proved: committed or not, the title before and after,
     * and the trail-row count on entry and on exit — the numbers that make
     * rollback visible without trusting anyone's word for it.
     */
    public record TransferProof(boolean committed, String postId, String titleBefore, String titleAfter,
            String changedBy, int trailRowsBefore, int trailRowsAfter, String note) {
    }

    // ---- the boundary checks, same rules as the posts substrate ----

    private static UUID requirePostId(String postId) {
        if (postId == null || postId.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION, "postId is required"));
        }
        try {
            return UUID.fromString(postId);
        } catch (IllegalArgumentException notAUuid) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "postId must be a UUID — this demo writes through the posts substrate"));
        }
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank() || title.trim().length() > TITLE_MAX) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "title is required and at most " + TITLE_MAX + " characters after trimming"));
        }
        return title.trim();
    }

    private static String requireActor(String changedBy) {
        String actor = changedBy == null || changedBy.isBlank() ? "demo-operator" : changedBy.trim();
        if (actor.length() > 80) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "changedBy must be at most 80 characters (audit_log.actor's width)"));
        }
        return actor;
    }
}
