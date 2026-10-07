package guide.domain.files;

/**
 * The upload use case's typed answer — a value, not an exception, so the
 * presentation edge maps each case to HTTP itself:
 *
 * - {@link Stored} → 201 with the record (the response that teaches: the
 *   sniffed type beside what the client claimed, the observed size, the
 *   sha256 that will become the ETag);
 * - {@link Rejected} with BAD_NAME / EMPTY_FILE → the app's 400
 *   validation envelope (the shared taxonomy's own kind);
 * - {@link Rejected} with TOO_LARGE → a 413 with the SAME envelope shape
 *   — a status the app's eight-kind taxonomy deliberately does not carry,
 *   because only this endpoint can ever produce it; the domain types the
 *   decision, the edge renders it (the pattern for domain-specific
 *   statuses without growing the global taxonomy).
 */
public sealed interface UploadOutcome {

    /** The stored record plus what the sniffing decided, for the honest 201 body. */
    record Stored(FileRecord record, String claimedContentType, String servedContentType) implements UploadOutcome {
    }

    /** Why an upload never reached the store. */
    enum Rejection {BAD_NAME, EMPTY_FILE, TOO_LARGE}

    record Rejected(Rejection reason, String message, long maxBytes, long receivedBytes) implements UploadOutcome {
    }
}
