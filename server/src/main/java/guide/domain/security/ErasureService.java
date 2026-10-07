package guide.domain.security;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;
import java.util.Map;

/**
 * The right-to-be-forgotten handler — the use case the whole PII vault
 * exists for. Erasure here is CRYPTO-SHRED: destroy the subject's key and
 * leave the ciphertext rows exactly where they are. The rows in the vault,
 * its backups, its replicas, yesterday's pg_dump — all of it becomes noise
 * nobody can read, because the one key that made it readable no longer
 * exists. That is why deleting rows is not enough: you cannot delete what
 * you cannot enumerate (backups, replicas, logs), but you CAN delete one
 * key.
 *
 * The erasure is then WITNESSED: an append-only log entry records subject,
 * key id, action, time. GDPR-style erasure is a legal promise — an auditor
 * asks "prove it happened", and the log is the proof.
 */
public class ErasureService {

    /** What erasure returns: the shred receipt (what survived, what died, the evidence id). */
    public record Erased(String subject, String keyId, int ciphertextFieldsStillStored,
            boolean dataUnreadableForever, String logEntryId, String erasedAt) {
    }

    private final PiiVault vault;
    private final KeyVault keyVault;
    private final ErasureLog erasureLog;
    private final Clock clock;

    public ErasureService(PiiVault vault, KeyVault keyVault, ErasureLog erasureLog, Clock clock) {
        this.vault = vault;
        this.keyVault = keyVault;
        this.erasureLog = erasureLog;
        this.clock = clock;
    }

    public Result<Erased> execute(String subject) {
        if (subject == null || subject.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION, "subject is required"));
        }
        String subjectId = subject.trim();
        var record = vault.find(subjectId).orElse(null);
        String keyId = keyVault.keyId(subjectId).orElse(null);
        if (record == null && keyId == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "nothing stored for subject \"" + subjectId + "\" — cannot erase what was never kept",
                    Map.of("subject", subjectId)));
        }
        boolean keyExisted = keyVault.destroyKey(subjectId);
        int fields = record == null ? 0 : record.encryptedFields().size();
        ErasureLogEntry entry = erasureLog.append(subjectId, keyId == null ? "unknown-key" : keyId,
                "right-to-be-forgotten: per-subject key destroyed; " + fields
                        + " ciphertext field(s) retained as permanently unreadable bytes");
        return Result.success(new Erased(subjectId, keyId, fields, keyExisted,
                entry.id(), entry.at().toString()));
    }
}
