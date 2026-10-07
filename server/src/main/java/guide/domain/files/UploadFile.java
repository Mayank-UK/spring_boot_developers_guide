package guide.domain.files;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The upload use case — every safety lesson of this topic in one flow:
 * sanitize the name, then drain the content ONCE through a pipeline that
 * guards the cap (GuardedUploadStream) and digests as it flows (the JDK's
 * DigestInputStream), and decide the stored content type from the bytes,
 * never from the claim. The store receives the wrapped stream, so when the
 * copy finishes the hash, head, and size are already known — no second
 * read of the file, ever. Only then is the metadata sidecar written, so a
 * rejected upload (bad name, empty stream, cap crossed) never leaves
 * metadata behind.
 */
public final class UploadFile {

    private final FileStore store;
    private final long maxBytes;

    public UploadFile(FileStore store, long maxBytes) {
        this.store = store;
        this.maxBytes = maxBytes;
    }

    /**
     * Executes one upload. The caller supplies the RAW content stream and
     * the client's filename/content-type claims; the answer is a typed
     * value, and any infrastructure failure leaves as an
     * IllegalStateException for the global handler's 500 envelope.
     */
    public UploadOutcome execute(String rawFilename, String claimedContentType, InputStream content) {
        String filename = SanitizedFilename.sanitize(rawFilename).orElse(null);
        if (filename == null) {
            return new UploadOutcome.Rejected(UploadOutcome.Rejection.BAD_NAME,
                    "no honest filename remains after sanitizing (blank, pure traversal, or control-only)",
                    maxBytes, 0);
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            GuardedUploadStream guard = new GuardedUploadStream(content, maxBytes);
            FileRecord bytes = store.save(FileRecord.pendingOriginal(filename, "application/octet-stream"),
                    new DigestInputStream(guard, sha256));
            if (guard.receivedBytes() == 0) {
                store.delete(bytes.id());
                return new UploadOutcome.Rejected(UploadOutcome.Rejection.EMPTY_FILE,
                        "an upload must carry at least one byte", maxBytes, 0);
            }
            String servedType = SniffedContentType.sniff(guard.head(), claimedContentType);
            FileRecord complete = new FileRecord(bytes.id(), bytes.filename(), servedType,
                    bytes.size(), HexFormat.of().formatHex(sha256.digest()), bytes.uploadedAt(), null);
            return new UploadOutcome.Stored(store.saveMetadata(complete),
                    claimedContentType == null ? "(none claimed)" : claimedContentType, servedType);
        } catch (GuardedUploadStream.Exceeded exceeded) {
            return new UploadOutcome.Rejected(UploadOutcome.Rejection.TOO_LARGE,
                    "upload exceeded the configured cap",
                    exceeded.maxBytes(), exceeded.receivedBytes());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("the file store rejected the upload: " + failure.getMessage(),
                    failure);
        }
    }
}
