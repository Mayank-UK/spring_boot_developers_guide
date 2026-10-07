package guide.domain.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The document record's compact constructor is the schema MongoDB will not
 * enforce — every invariant a visit promises gets judged HERE, at every
 * door that can build one (topic 10's hand-rolled stance, the wall the
 * validation section quotes). Each case is a BUILDER lambda: the record
 * cannot exist invalid, so the test must observe the rejection, not the
 * bad object.
 */
class VisitDocumentTest {

    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");
    private static final String HASH = VisitDocument.sha256Hex("reader-one");

    record BadCase(String label, Supplier<VisitDocument> builder) {
    }

    static Stream<BadCase> invalid() {
        return Stream.of(
                new BadCase("blank id", () -> new VisitDocument(
                        "", "jdbc-sql", "/topics/jdbc-sql", HASH, 1, T, "en")),
                new BadCase("uppercase topic", () -> new VisitDocument(
                        "id", "JDBC-SQL", "/topics/jdbc-sql", HASH, 1, T, "en")),
                new BadCase("spaced topic", () -> new VisitDocument(
                        "id", "jdbc sql", "/topics/jdbc-sql", HASH, 1, T, "en")),
                new BadCase("relative path", () -> new VisitDocument(
                        "id", "jdbc-sql", "topics/jdbc-sql", HASH, 1, T, "en")),
                new BadCase("raw visitor token as hash", () -> new VisitDocument(
                        "id", "jdbc-sql", "/topics/jdbc-sql", "reader-one", 1, T, "en")),
                new BadCase("negative duration", () -> new VisitDocument(
                        "id", "jdbc-sql", "/topics/jdbc-sql", HASH, -1, T, "en")),
                new BadCase("absurd duration", () -> new VisitDocument(
                        "id", "jdbc-sql", "/topics/jdbc-sql", HASH,
                        VisitDocument.MAX_DURATION_MS + 1, T, "en")),
                new BadCase("missing instant", () -> new VisitDocument(
                        "id", "jdbc-sql", "/topics/jdbc-sql", HASH, 1, null, "en")),
                new BadCase("language name as locale", () -> new VisitDocument(
                        "id", "jdbc-sql", "/topics/jdbc-sql", HASH, 1, T, "english")),
                new BadCase("country-only locale", () -> new VisitDocument(
                        "id", "jdbc-sql", "/topics/jdbc-sql", HASH, 1, T, "DE")));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void malformedDocumentsCannotExist(BadCase bad) {
        // IllegalArgumentException for rule violations; NullPointerException
        // for the one requireNonNull (missing instant) — both mean rejected.
        assertThrows(RuntimeException.class, () -> bad.builder().get(),
                "compact constructor must reject: " + bad.label());
    }

    @Test
    void theValidDocumentPassesUntouched() {
        VisitDocument visit = new VisitDocument("507f1f77bcf86cd799439011", "jdbc-sql",
                "/topics/jdbc-sql", HASH, 84_000, T, "en");
        assertEquals("jdbc-sql", visit.topicId());
        assertEquals("/topics/jdbc-sql", visit.path());
        assertEquals("en", visit.locale());
    }

    @Test
    void visitorHashingIsDeterministicAndHex64() {
        String hash = VisitDocument.sha256Hex("reader-one");
        assertEquals(64, hash.length());
        assertEquals(true, hash.matches("^[0-9a-f]{64}$"), "lowercase hex, sha-256 shape");
        assertEquals(hash, VisitDocument.sha256Hex("reader-one"),
                "same token, same hash — the fake seed depends on it");
    }
}
