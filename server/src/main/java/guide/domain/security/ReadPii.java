package guide.domain.security;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Use case: read one subject's data back — the before/after half of the
 * crypto-shred demo. Two REFUSAL shapes, deliberately different:
 *
 * - nothing stored → plain 404 (unknown subject);
 * - rows exist but the key is GONE → 404 carrying {erased: true}: the data
 *   is present as ciphertext and permanently undecryptable. Same HTTP class,
 *   different story — the envelope's details say which, and the demo renders
 *   exactly that difference.
 */
public class ReadPii {

    /** The decrypted view: plaintext only ever lives here, on request, in memory. */
    public record View(String subject, String keyId, Map<String, String> fields,
            String algorithm, String storedAt) {
    }

    private final PiiVault vault;
    private final KeyVault keyVault;

    public ReadPii(PiiVault vault, KeyVault keyVault) {
        this.vault = vault;
        this.keyVault = keyVault;
    }

    public Result<View> execute(String subject) {
        if (subject == null || subject.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION, "subject is required"));
        }
        var record = vault.find(subject.trim()).orElse(null);
        if (record == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "no data stored for subject \"" + subject.trim() + "\""));
        }
        var key = keyVault.key(record.subjectId()).orElse(null);
        if (key == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "subject \"" + record.subjectId() + "\" has " + record.encryptedFields().size()
                            + " ciphertext field(s) stored, but the key that could read them was "
                            + "destroyed at erasure — undecryptable by design",
                    Map.of("erased", true, "keyId", record.keyId(),
                            "ciphertextFields", record.encryptedFields().size())));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> sealed : record.encryptedFields().entrySet()) {
            try {
                fields.put(sealed.getKey(), AesGcmFieldCipher.decrypt(key, sealed.getValue()));
            } catch (IllegalStateException tampered) {
                // GCM tag failure: the ciphertext changed after sealing — data
                // integrity is the breach story here, reported, never papered over
                return Result.failed(Failure.of(FailureKind.INTERNAL,
                        "field \"" + sealed.getKey() + "\" failed AES-GCM authentication — "
                                + "the stored ciphertext was tampered with or corrupted",
                        Map.of("subject", record.subjectId(), "field", sealed.getKey())));
            }
        }
        return Result.success(new View(record.subjectId(), record.keyId(), fields,
                record.algorithm(), record.storedAt().toString()));
    }
}
