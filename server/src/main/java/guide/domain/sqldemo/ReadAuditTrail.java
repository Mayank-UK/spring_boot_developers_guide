package guide.domain.sqldemo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.sqldemo.AuditTrailStore.TrailEntry;
import java.util.List;
import java.util.UUID;

/**
 * Topic 17's trail reader (demo db:audit, GET side): validate the entity id,
 * read the trail. An unknown-but-well-formed id is an EMPTY trail, not a
 * 404 — a ledger's answer to "what happened around this row?" is legitimately
 * "nothing", and conflating that with "no such resource" would teach the
 * wrong shape.
 */
public final class ReadAuditTrail {

    private final AuditTrailStore store;

    public ReadAuditTrail(AuditTrailStore store) {
        this.store = store;
    }

    /** The trail for one entity id, oldest first. */
    public List<TrailEntry> read(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION, "entityId is required"));
        }
        try {
            UUID.fromString(entityId);
        } catch (IllegalArgumentException notAUuid) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "entityId must be a UUID — trail lookups key on the posts substrate's ids"));
        }
        return store.trailFor(entityId);
    }
}
