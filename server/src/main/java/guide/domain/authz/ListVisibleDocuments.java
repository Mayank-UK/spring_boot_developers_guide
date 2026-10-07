package guide.domain.authz;

import guide.domain.auth.UserPrincipal;
import java.util.List;

/**
 * Use case behind GET /api/demo/authz/documents: the caller's document
 * list, FILTERED BY POLICY at the use-case layer. This is the
 * query-shapes-authorization lesson made real: the store returns ALL
 * documents; the policy decides which rows may leave; what the caller
 * never sees, the caller cannot leak. The SQL translation (WHERE
 * owner = :me OR visibility = 'public' OR …) is the ownership section's
 * whole point — same decision, pushed into the query.
 *
 * Every visible row carries its reason, so the list doubles as the
 * explanation of the filter that produced it.
 */
public final class ListVisibleDocuments {

    /** A row the caller may see, annotated with WHY it is visible. */
    public record VisibleDocument(OwnedDocument document, String visibleBecause, String checkedBy) {

        static VisibleDocument of(OwnedDocument document, String reason) {
            return new VisibleDocument(document, reason, "ownership-policy");
        }
    }

    private final DocumentStore documentStore;
    private final OwnershipPolicy ownershipPolicy;

    public ListVisibleDocuments(DocumentStore documentStore, OwnershipPolicy ownershipPolicy) {
        this.documentStore = documentStore;
        this.ownershipPolicy = ownershipPolicy;
    }

    public List<VisibleDocument> execute(UserPrincipal caller) {
        return documentStore.findAll().stream()
                .filter(document -> ownershipPolicy.canRead(caller, document))
                .map(document -> VisibleDocument.of(document,
                        ownershipPolicy.readReason(caller, document)))
                .toList();
    }
}
