package guide.domain.thirdparty;

/**
 * The object-storage port — S3-shaped: flat keys, opaque provider etags,
 * and a size ceiling the provider enforces. Object storage is the
 * integration whose failure modes are quietest: a put that silently
 * throttles, a key scheme that invites path traversal, a bucket that
 * outlives the app that owns it. The port keeps the surface small on
 * purpose — put (with the provider's etag back) is the operation the hub
 * exercises; presigned URLs, multipart uploads and lifecycle rules are
 * provider vocabulary taught in prose, not surface the domain needs.
 *
 * The guide's adapter is an in-process store with provider-shaped
 * discipline: keys are validated (no {@code ..} segments, no leading
 * slash), etags are deterministic digests, and the object count is
 * BOUNDED — the guide's bounded-everything rule applied to a fake.
 */
public non-sealed interface StoragePort extends IntegrationPort {

    /**
     * Put one object under the given key. THROWS typed VALIDATION for a
     * malformed key (the caller's input was bad). Provider-shaped refusals
     * (over the sandbox provider's size ceiling, say) come BACK as failed
     * receipts — they are the provider's answer, not this app's outage.
     */
    StoredReceipt put(String key, int sizeBytes);

    /** The provider's answer: the key back, its etag, and the size recorded. */
    record StoredReceipt(String outcome, String key, String etag, int bytes, String detail) {
    }
}
