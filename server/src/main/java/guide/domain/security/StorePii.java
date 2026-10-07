package guide.domain.security;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Use case: seal one subject's personal data into the vault. The order of
 * operations IS the design: mint the per-subject key FIRST, encrypt every
 * field with it, then store only ciphertext. The plaintext exists inside
 * this method's stack frame and nowhere else — not in the vault, not in the
 * log, not in the response.
 *
 * Storing over an existing subject is refused with CONFLICT: an honest
 * erasure contract does not silently re-key. Erase first (the old key dies,
 * the old rows become noise), then store again if a subject returns.
 */
public class StorePii {

    /** The caller's view: ciphertext previews (truncated) + the key id — never plaintext back. */
    public record Stored(String subject, String keyId, Map<String, String> ciphertextPreview,
            String algorithm, String storedAt, int fieldCount) {
    }

    /** Request: {subject: "usr_42", fields: {name: "…", email: "…"}}. */
    public record Request(String subject, Map<String, String> fields) {
    }

    private static final int PREVIEW_CHARS = 28;

    private final PiiVault vault;
    private final KeyVault keyVault;
    private final Clock clock;

    public StorePii(PiiVault vault, KeyVault keyVault, Clock clock) {
        this.vault = vault;
        this.keyVault = keyVault;
        this.clock = clock;
    }

    public Result<Stored> execute(Request request) {
        if (request == null || isBlank(request.subject())) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "subject is required — personal data belongs to a named data subject"));
        }
        if (request.fields() == null || request.fields().isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "at least one field is required"));
        }
        String subject = request.subject().trim();
        for (Map.Entry<String, String> field : request.fields().entrySet()) {
            if (isBlank(field.getKey()) || field.getValue() == null) {
                return Result.failed(Failure.of(FailureKind.VALIDATION,
                        "field names and values must be present: " + field.getKey()));
            }
        }
        // A LIVE subject (rows present AND key present) is a conflict — silent
        // re-keying would betray the erasure contract's next run. An ERASED
        // subject (rows present, key gone) may store again: the surviving rows
        // are unreadable noise, and a fresh key simply overwrites them.
        if (vault.find(subject).isPresent() && keyVault.key(subject).isPresent()) {
            return Result.failed(Failure.of(FailureKind.CONFLICT,
                    "subject \"" + subject + "\" is already stored — erase first (the crypto-shred contract: "
                            + "no silent re-keying)", Map.of("subject", subject)));
        }
        String keyId = keyVault.createKey(subject);
        var key = keyVault.key(subject).orElseThrow();
        Map<String, String> sealed = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : request.fields().entrySet()) {
            sealed.put(field.getKey(), AesGcmFieldCipher.encrypt(key, field.getValue()));
        }
        var record = new PiiRecord(subject, keyId, sealed, PiiRecord.ALGORITHM, clock.instant());
        vault.save(record);
        Map<String, String> preview = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : sealed.entrySet()) {
            preview.put(field.getKey(), truncate(field.getValue()));
        }
        return Result.success(new Stored(subject, keyId, preview, record.algorithm(),
                record.storedAt().toString(), sealed.size()));
    }

    private static String truncate(String value) {
        return value.length() <= PREVIEW_CHARS ? value : value.substring(0, PREVIEW_CHARS) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
