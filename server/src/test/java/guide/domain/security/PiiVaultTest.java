package guide.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.security.InMemoryErasureLog;
import guide.data.security.InMemoryKeyVault;
import guide.data.security.InMemoryPiiVault;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The crypto-shred lifecycle, the whole before/after story as one
 * deterministic test class: seal → read-back works; the same plaintext
 * seals differently twice (random IVs); a second store is a CONFLICT; the
 * erasure destroys exactly the key (ciphertext rows stay); the read after
 * erasure is the TYPED undecryptable failure; the log is append-only.
 */
class PiiVaultTest {

    private static final Clock CLOCK = Clock.systemUTC();

    private final KeyVault keyVault = new InMemoryKeyVault();
    private final PiiVault vault = new InMemoryPiiVault();
    private final ErasureLog erasureLog = new InMemoryErasureLog(CLOCK);
    private final StorePii storePii = new StorePii(vault, keyVault, CLOCK);
    private final ReadPii readPii = new ReadPii(vault, keyVault);
    private final ErasureService erasureService = new ErasureService(vault, keyVault, erasureLog, CLOCK);

    private static StorePii.Request subject(String id, String name, String email) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("email", email);
        return new StorePii.Request(id, fields);
    }

    private static <T> T value(Result<T> result) {
        return ((Success<T>) result).value();
    }

    private static <T> Failed<T> failed(Result<T> result) {
        return (Failed<T>) result;
    }

    @Test
    void theRoundTripWorksAndTheStoreNeverHoldsPlaintext() {
        StorePii.Stored stored = value(storePii.execute(subject("usr_1", "Ada Lovelace", "ada@example.com")));
        assertEquals("key_", stored.keyId().substring(0, 4));
        assertEquals(2, stored.fieldCount());
        assertTrue(stored.ciphertextPreview().get("name").endsWith("…"),
                "previews are truncated ciphertext, never plaintext");

        ReadPii.View view = value(readPii.execute("usr_1"));
        assertEquals(Map.of("name", "Ada Lovelace", "email", "ada@example.com"), view.fields());
    }

    @Test
    void theSamePlaintextSealsDifferentlyTwice() {
        storePii.execute(subject("usr_2", "Same Name", "same@example.com"));
        storePii.execute(subject("usr_3", "Same Name", "same@example.com"));
        PiiRecord first = vault.find("usr_2").orElseThrow();
        PiiRecord second = vault.find("usr_3").orElseThrow();
        assertNotEquals(first.encryptedFields().get("name"), second.encryptedFields().get("name"),
                "fresh random IV per encryption — identical plaintext, different ciphertext");
    }

    @Test
    void storingOverAnExistingSubjectIsAConflictNeverAReKey() {
        storePii.execute(subject("usr_4", "One", "one@example.com"));
        var second = storePii.execute(subject("usr_4", "Two", "two@example.com"));
        assertEquals(FailureKind.CONFLICT, failed(second).failure().kind());
    }

    @Test
    void aReturnedSubjectStoresAgainAfterErasureUnderAFreshKey() {
        storePii.execute(subject("usr_7", "Gone", "gone7@example.com"));
        String firstKey = keyVault.keyId("usr_7").orElseThrow();
        erasureService.execute("usr_7");

        StorePii.Stored again = value(storePii.execute(subject("usr_7", "Back", "back7@example.com")));
        assertNotEquals(firstKey, again.keyId(), "the return is a NEW key — the old one is dead");
        assertEquals("Back", value(readPii.execute("usr_7")).fields().get("name"));
    }

    @Test
    void rowsSurviveTheKeyDoesNotAndTheReadAfterIsTheTypedUndecryptableFailure() {
        storePii.execute(subject("usr_5", "Erased Person", "gone@example.com"));
        assertTrue(readPii.execute("usr_5").isSuccess(), "readable before erasure");

        ErasureService.Erased erased = value(erasureService.execute("usr_5"));
        assertTrue(erased.dataUnreadableForever());
        assertEquals(2, erased.ciphertextFieldsStillStored(),
                "the ciphertext rows are deliberately retained");

        // the vault STILL has the rows — the shred was the key, not the delete
        assertTrue(vault.find("usr_5").isPresent());
        assertTrue(keyVault.key("usr_5").isEmpty(), "the key is gone");

        var after = readPii.execute("usr_5");
        assertEquals(FailureKind.NOT_FOUND, failed(after).failure().kind());
        assertEquals(Boolean.TRUE, failed(after).failure().details().get("erased"),
                "the typed failure says WHICH not-found this is: erased, not never-stored");
        assertEquals(2, failed(after).failure().details().get("ciphertextFields"));
    }

    @Test
    void readingAnUnknownSubjectIsThePlainNotFoundNotTheErasedOne() {
        var result = readPii.execute("usr_never");
        assertEquals(FailureKind.NOT_FOUND, failed(result).failure().kind());
        assertNull(failed(result).failure().details());
    }

    @Test
    void theErasureLogIsAppendOnlyAndCarriesTheEvidence() {
        storePii.execute(subject("usr_6", "Logged", "log@example.com"));
        erasureService.execute("usr_6");
        List<ErasureLogEntry> first = erasureLog.entries();
        assertEquals(1, first.size());
        assertEquals("usr_6", first.getFirst().subjectId());
        assertTrue(first.getFirst().action().contains("key destroyed"));

        erasureService.execute("usr_6"); // re-erase: a second receipt, never a rewrite
        List<ErasureLogEntry> second = erasureLog.entries();
        assertEquals(2, second.size());
        assertEquals(first.getFirst().id(), second.getFirst().id(),
                "earlier entries are immutable — append-only");
    }

    @Test
    void validationAndUnknownSubjectErasureAreTypedFailures() {
        var blank = storePii.execute(new StorePii.Request(" ", Map.of()));
        assertEquals(FailureKind.VALIDATION, failed(blank).failure().kind());
        var ghost = erasureService.execute("ghost");
        assertEquals(FailureKind.NOT_FOUND, failed(ghost).failure().kind());
        assertTrue(ghost instanceof Failed, "erasing a ghost produces no receipt");
    }
}
