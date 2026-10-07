package guide.data.thirdparty;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.thirdparty.Posture;
import guide.domain.thirdparty.StoragePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Adapter: the in-process object store — S3-shaped discipline with no
 * bucket. What that means, mechanically:
 *
 * <ul>
 *   <li>KEYS ARE VALIDATED — the accepted alphabet plus a ban on {@code ..}
 *       segments (the path-traversal guard topic 26 taught for local
 *       files, applied to provider keys: a key like {@code ../../secrets}
 *       must die at YOUR boundary, not inside the provider's SDK).</li>
 *   <li>ETAGS ARE DIGESTS — the provider answers a put with an opaque
 *       etag; the fake's etag is the first 16 hex chars of SHA-256 over
 *       key + size, deterministic so tests can pin it.</li>
 *   <li>THE STORE IS BOUNDED — 64 objects, oldest evicted: the guide's
 *       bounded-everything rule (scaling topic) applied to demo state.</li>
 *   <li>SANDBOX ENFORCES A PROVIDER CEILING — the sandbox provider caps
 *       objects at 10240 bytes and answers EntityTooLarge as a FAILED
 *       RECEIPT (value, not exception): the provider's answer to a
 *       request it refuses, exactly as the real API does.</li>
 * </ul>
 */
public final class InMemoryObjectStore implements StoragePort {

    /** The sandbox provider's documented object ceiling. */
    static final int SANDBOX_MAX_BYTES = 10_240;

    /** The fake store's own bound — demo state must not grow unbounded. */
    static final int MAX_OBJECTS = 64;

    private static final Pattern KEY_SHAPE = Pattern.compile("[a-z0-9][a-z0-9/._-]{0,120}");

    /** What one stored object looks like in the fake. */
    public record StoredObject(String key, String etag, int bytes) {
    }

    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, StoredObject> objects = new LinkedHashMap<>();
    private volatile Posture posture = Posture.FAKE;

    @Override
    public StoredReceipt put(String key, int sizeBytes) {
        validateKey(key);
        if (posture == Posture.SANDBOX && sizeBytes > SANDBOX_MAX_BYTES) {
            return new StoredReceipt("failed", key, null, 0,
                    "EntityTooLarge — the sandbox provider caps objects at " + SANDBOX_MAX_BYTES
                            + " bytes (your put was " + sizeBytes + ")");
        }
        String etag = digest(key, sizeBytes);
        synchronized (this) {
            objects.put(key, new StoredObject(key, etag, sizeBytes));
            while (objects.size() > MAX_OBJECTS) {
                var oldest = objects.keySet().iterator().next();
                objects.remove(oldest);
            }
        }
        return new StoredReceipt("stored", key, etag, sizeBytes,
                (posture == Posture.SANDBOX ? "the sandbox provider stored" : "the fake store holds")
                        + " object " + sequence.incrementAndGet() + " under " + key);
    }

    @Override
    public Posture posture() {
        return posture;
    }

    @Override
    public boolean adoptPosture(Posture next) {
        if (next == Posture.FAKE || next == Posture.SANDBOX) {
            posture = next;
            return true;
        }
        return false;
    }

    @Override
    public synchronized void reset() {
        objects.clear();
        sequence.set(0);
        posture = Posture.FAKE;
    }

    /** The stored objects, snapshot for consoles/tests. */
    public synchronized Map<String, StoredObject> objects() {
        return Map.copyOf(objects);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || !KEY_SHAPE.matcher(key).matches()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "object keys are lowercase provider-shaped paths like guide/hello.txt",
                    Map.of("parameter", "key")));
        }
        for (String segment : key.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw AppException.of(Failure.of(FailureKind.VALIDATION,
                        "key segment '" + segment + "' is a path traversal — rejected at the boundary",
                        Map.of("parameter", "key")));
            }
        }
    }

    /** Deterministic provider-shaped etag: first 16 hex of SHA-256(key:size). */
    private static String digest(String key, int sizeBytes) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest((key + ":" + sizeBytes).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            // SHA-256 is mandated by the JDK spec — unreachable by construction
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
