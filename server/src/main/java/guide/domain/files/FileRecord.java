package guide.domain.files;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored file's METADATA — the record the store keeps beside the bytes.
 * The bytes live on disk under the id; this record is what every HTTP answer
 * about the file is built from. Note what it does NOT carry: the client's
 * claimed content type is deliberately absent — the SNIFFED type is stored
 * (the upload lesson: never trust the claim), and the filename here is the
 * SANITIZED display name, never a filesystem path.
 *
 * {@code derivedFrom} is null for originals and names the source file for
 * derived variants (thumbnails): a derived record is a full FileRecord —
 * its own id, its own sha256 — that simply remembers where it came from,
 * so listing the store shows provenance without a second table.
 */
public record FileRecord(
        String id,
        String filename,
        String contentType,
        long size,
        String sha256,
        Instant uploadedAt,
        String derivedFrom) {

    /** A fresh original: random id (unguessable ids are this store's only access control), size/sha set by the store. */
    public static FileRecord pendingOriginal(String filename, String contentType) {
        return new FileRecord(UUID.randomUUID().toString(), filename, contentType, -1, null, Instant.now(), null);
    }

    /** A fresh derived variant: own id, own digest, remembers its source. */
    public static FileRecord pendingDerived(String filename, String contentType, String derivedFrom) {
        return new FileRecord(UUID.randomUUID().toString(), filename, contentType, -1, null, Instant.now(), derivedFrom);
    }

    /** True when this record was produced from another stored file, not uploaded. */
    public boolean isDerived() {
        return derivedFrom != null;
    }

    /** Copy with the OBSERVED byte size filled in — what save/produce hand back after the copy. */
    public FileRecord withSize(long observedSize) {
        return new FileRecord(id, filename, contentType, observedSize, sha256, uploadedAt, derivedFrom);
    }

    /** The strong ETag for this file's exact bytes: the content hash IS the validator. */
    public String etag() {
        return "\"" + sha256 + "\"";
    }
}
