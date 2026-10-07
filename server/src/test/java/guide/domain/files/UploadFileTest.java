package guide.domain.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.data.files.DiskFileStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * UploadFile at the unit level, against the REAL disk adapter over a
 * @TempDir: the sanitizer's traversal battery (plain, backslash,
 * absolute, percent-encoded, pure-traversal), the mid-stream cap with
 * adapter cleanup proof, sha256-while-streaming verified against an
 * independently computed digest, content-type authority (magic beats the
 * claim), and the empty-file rejection. No Spring context anywhere.
 */
class UploadFileTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};

    @TempDir
    Path storeDir;

    private DiskFileStore store() throws Exception {
        return new DiskFileStore(storeDir, JsonMapper.builder().build());
    }

    private static ByteArrayInputStream bytes(byte... content) {
        return new ByteArrayInputStream(content);
    }

    private static byte[] times(int count, byte filler) {
        byte[] filled = new byte[count];
        java.util.Arrays.fill(filled, filler);
        return filled;
    }

    @Test
    void storesSniffedTypeOverTheClientsClaimAndDigestsWhileStreaming() throws Exception {
        DiskFileStore store = store();
        UploadOutcome outcome = new UploadFile(store, 1024)
                .execute("photo.png", "text/html", bytes(PNG_MAGIC)); // the XSS attempt: html label, PNG bytes

        UploadOutcome.Stored stored = assertInstanceOf(UploadOutcome.Stored.class, outcome);
        assertEquals("image/png", stored.servedContentType()); // magic WINS, claim recorded for the lesson
        assertEquals("text/html", stored.claimedContentType());
        assertEquals(PNG_MAGIC.length, stored.record().size());

        // digest computed while streaming == an independent recompute (never a second read of the file)
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        assertEquals(HexFormat.of().formatHex(expected.digest(PNG_MAGIC)), stored.record().sha256());
        assertEquals("\"" + stored.record().sha256() + "\"", stored.record().etag());
    }

    @Test
    void textClaimBelievedOnlyWhenBytesCorroborateIt() throws Exception {
        DiskFileStore store = store();
        UploadFile upload = new UploadFile(store, 1024);

        UploadOutcome.Stored text = assertInstanceOf(UploadOutcome.Stored.class,
                upload.execute("notes.txt", "text/plain", bytes("just words".getBytes())));
        assertEquals("text/plain", text.servedContentType());

        UploadOutcome.Stored binary = assertInstanceOf(UploadOutcome.Stored.class,
                upload.execute("trap.txt", "text/plain", bytes(new byte[]{0, 1, 2, 0, 9})));
        assertEquals("application/octet-stream", binary.servedContentType()); // NUL bytes betray the claim
    }

    @Test
    void traversalFilenamesAreSanitizedToTheirBasename() throws Exception {
        DiskFileStore store = store();
        UploadFile upload = new UploadFile(store, 1024);

        assertEquals("secrets.txt", stored(upload.execute("../secrets.txt", null, bytes(times(3, (byte) 1))))
                .record().filename());
        assertEquals("win.ini", stored(upload.execute("..\\..\\win.ini", null, bytes(times(3, (byte) 2))))
                .record().filename());
        assertEquals("passwd", stored(upload.execute("/etc/passwd", null, bytes(times(3, (byte) 3))))
                .record().filename());
        // percent-encoded traversal decodes and then loses its path components the same way
        assertEquals("passwd", stored(upload.execute("..%2F..%2Fetc%2Fpasswd", null, bytes(times(3, (byte) 4))))
                .record().filename());
    }

    @Test
    void pureTraversalNamesAreRejectedOutright() {
        DiskFileStore store = newStoreQuietly();
        UploadFile upload = new UploadFile(store, 1024);

        for (String hostile : new String[]{"..", "../", "..\\..", null, "  ", "\t"}) {
            UploadOutcome outcome = upload.execute(hostile, null, bytes(times(2, (byte) 9)));
            UploadOutcome.Rejected rejected = assertInstanceOf(UploadOutcome.Rejected.class, outcome,
                    "filename \"" + hostile + "\" must be rejected");
            assertEquals(UploadOutcome.Rejection.BAD_NAME, rejected.reason());
        }
        // a name that is nothing BUT encoded traversal decodes to nothing honest
        assertEquals(UploadOutcome.Rejection.BAD_NAME,
                assertInstanceOf(UploadOutcome.Rejected.class,
                        upload.execute("%2e%2e%2f", null, bytes(times(2, (byte) 9)))).reason());
    }

    @Test
    void capCrossedMidStreamRejectsWithEvidenceAndLeavesNoOrphan() throws Exception {
        DiskFileStore store = store();
        UploadFile upload = new UploadFile(store, 10);

        UploadOutcome outcome = upload.execute("big.bin", "application/octet-stream",
                bytes(times(11, (byte) 7)));
        UploadOutcome.Rejected rejected = assertInstanceOf(UploadOutcome.Rejected.class, outcome);
        assertEquals(UploadOutcome.Rejection.TOO_LARGE, rejected.reason());
        assertEquals(10, rejected.maxBytes());
        assertEquals(11, rejected.receivedBytes());

        // the adapter cleaned its partial target: the store dir has no bytes file
        try (var entries = Files.list(storeDir)) {
            assertTrue(entries.allMatch(path -> !path.getFileName().toString().endsWith(".bin")),
                    "a rejected upload must leave no bytes behind");
        }
        assertEquals(List.of(), store.list());
    }

    @Test
    void emptyStreamIsRejectedAndCleanedUp() throws Exception {
        DiskFileStore store = store();
        UploadOutcome outcome = new UploadFile(store, 1024).execute("empty.txt", "text/plain", bytes());

        assertEquals(UploadOutcome.Rejection.EMPTY_FILE,
                assertInstanceOf(UploadOutcome.Rejected.class, outcome).reason());
        assertEquals(List.of(), store.list());
        try (var entries = Files.list(storeDir)) {
            assertTrue(entries.allMatch(path -> !path.getFileName().toString().endsWith(".bin")),
                    "an empty rejection must leave no bytes behind");
        }
    }

    @Test
    void controlCharactersNeverReachTheStoredName() throws Exception {
        DiskFileStore store = store();
        UploadOutcome.Stored stored = stored(new UploadFile(store, 1024)
                .execute("evil\r\nSet-Cookie: x=1.txt", null, bytes(times(2, (byte) 5))));
        assertEquals("evilSet-Cookie: x=1.txt", stored.record().filename()); // CR/LF stripped, rest kept
    }

    // ---- helpers ----

    private static UploadOutcome.Stored stored(UploadOutcome outcome) {
        return assertInstanceOf(UploadOutcome.Stored.class, outcome);
    }

    private DiskFileStore newStoreQuietly() {
        try {
            return store();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
