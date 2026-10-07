package guide.domain.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ValidationArenaTest — the exhibit judged purely, no Spring: every violation
 * class fires its annotation, nulls pass the constraints that should skip
 * them, the cross-field window behaves as three rules, the envelope preview
 * mirrors the global handler's mapping, and no verdict anywhere echoes the
 * submitted password VALUE (classification only).
 */
class ValidationArenaTest {

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);
    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    private final ValidationArena arena =
            new ValidationArena(Validation.buildDefaultValidatorFactory().getValidator());

    private static ArenaSubmission valid() {
        return new ArenaSubmission("Ada Lovelace", "ada@example.com", 87, YESTERDAY,
                "Sup3r$ecret!", YESTERDAY, LocalDate.now().plusDays(7));
    }

    private static Map<String, List<ValidationArena.Violation>> violationsByField(
            ValidationArena.ArenaVerdict verdict) {
        return verdict.perField().stream()
                .collect(Collectors.toMap(ValidationArena.FieldVerdict::field,
                        ValidationArena.FieldVerdict::violations));
    }

    private static List<String> annotationsOn(ValidationArena.ArenaVerdict verdict, String field) {
        return violationsByField(verdict).get(field).stream()
                .map(ValidationArena.Violation::annotation)
                .toList();
    }

    @Test
    void validSubmissionIsAcceptedWithEveryFieldClassifiedAccepted() {
        ValidationArena.ArenaVerdict verdict = arena.assess(valid(), "req-1");

        assertTrue(verdict.accepted());
        assertEquals(7, verdict.perField().size(), "seven components, no class-level row when valid");
        assertTrue(verdict.perField().stream()
                .allMatch(row -> "accepted".equals(row.valueClassification()) && row.violations().isEmpty()));
        assertNull(verdict.envelopeComparison(), "accepted payloads preview no envelope");
    }

    @Test
    void everyViolationClassFiresItsAnnotation() {
        ArenaSubmission hostile = new ArenaSubmission("", "not-an-email", 101, TOMORROW,
                "", LocalDate.now(), YESTERDAY);
        ValidationArena.ArenaVerdict verdict = arena.assess(hostile, "req-2");

        assertFalse(verdict.accepted());
        assertEquals(List.of("NotBlank"), annotationsOn(verdict, "name"));
        assertEquals(List.of("Email"), annotationsOn(verdict, "email"));
        assertEquals(List.of("Max"), annotationsOn(verdict, "score"));
        assertEquals(List.of("Past"), annotationsOn(verdict, "eventDate"));
        List<String> passwordAnnotations = annotationsOn(verdict, "password");
        assertEquals(2, passwordAnnotations.size(), "the built-in blank check and the policy both fire");
        assertTrue(passwordAnnotations.containsAll(List.of("NotBlank", "StrongPassword")));
        assertEquals(List.of("DateRange"), annotationsOn(verdict, ValidationArena.CLASS_LEVEL_FIELD));

        assertEquals("missing or blank", row(verdict, "name").valueClassification());
        assertEquals("not an email address", row(verdict, "email").valueClassification());
        assertEquals("outside numeric range", row(verdict, "score").valueClassification());
        assertEquals("date not in the past", row(verdict, "eventDate").valueClassification());
        assertEquals("policy violations", row(verdict, "password").valueClassification());
        assertEquals("cross-field: confirmStart ≤ confirmEnd",
                row(verdict, ValidationArena.CLASS_LEVEL_FIELD).valueClassification());
    }

    @Test
    void missingScoreFiresNotNullAndShortNameFiresSize() {
        ArenaSubmission payload = new ArenaSubmission("a".repeat(41), "a@b.co", null, null,
                "Sup3r$ecret!", null, null);
        ValidationArena.ArenaVerdict verdict = arena.assess(payload, "req-3");

        assertEquals(List.of("Size"), annotationsOn(verdict, "name"));
        assertEquals("length out of range", row(verdict, "name").valueClassification());
        assertEquals(List.of("NotNull"), annotationsOn(verdict, "score"));
        assertEquals("missing", row(verdict, "score").valueClassification());
    }

    @Test
    void negativeScoreFiresMin() {
        ValidationArena.ArenaVerdict verdict =
                arena.assess(new ArenaSubmission("Ada", "a@b.co", -1, null, "Sup3r$ecret!", null, null),
                        "req-4");

        assertEquals(List.of("Min"), annotationsOn(verdict, "score"));
    }

    @Test
    void nullPassesConstraintsThatSkipNullAndHalfPresentWindowFiresDateRange() {
        // eventDate null passes @Past; window absent passes; only score+password+window are exercised
        ArenaSubmission nullTolerant = new ArenaSubmission("Ada", "a@b.co", 50, null,
                "Sup3r$ecret!", YESTERDAY, null);
        ValidationArena.ArenaVerdict verdict = arena.assess(nullTolerant, "req-5");

        assertTrue(annotationsOn(verdict, "eventDate").isEmpty(), "@Past skips null by spec");
        assertEquals("accepted", row(verdict, "eventDate").valueClassification());
        assertEquals(List.of("DateRange"), annotationsOn(verdict, ValidationArena.CLASS_LEVEL_FIELD),
                "exactly one window date is a cross-field violation");
    }

    @Test
    void weakPasswordMessageNamesTheMissingRulesWithoutTheValue() {
        String weakPassword = "shadowsecretvalue";
        ValidationArena.ArenaVerdict verdict = arena.assess(
                new ArenaSubmission("Ada", "a@b.co", 50, null, weakPassword, null, null), "req-6");

        List<ValidationArena.Violation> passwordViolations = row(verdict, "password").violations();
        ValidationArena.Violation policy = passwordViolations.stream()
                .filter(violation -> "StrongPassword".equals(violation.annotation()))
                .findFirst().orElseThrow();
        assertTrue(policy.message().contains("an uppercase letter"));
        assertTrue(policy.message().contains("a digit"));
        assertTrue(policy.message().contains("a symbol"), "custom message lists what is missing");

        String everything = verdict.perField().stream()
                .flatMap(row -> row.violations().stream())
                .map(violation -> violation.annotation() + " " + violation.message() + " "
                        + row(verdict, "password").valueClassification())
                .collect(Collectors.joining());
        assertFalse(everything.contains(weakPassword), "the raw password never appears in the verdict");
        assertFalse(everything.contains(weakPassword.toUpperCase()), "not even case-shifted");
    }

    @Test
    void envelopePreviewMirrorsTheGlobalHandlerMapping() {
        ArenaSubmission hostile = new ArenaSubmission("", "not-an-email", 101, null,
                "weak", null, null);
        ValidationArena.ArenaVerdict verdict = arena.assess(hostile, "req-7");

        assertNotNull(verdict.envelopeComparison());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) verdict.envelopeComparison().get("error");
        assertEquals("validation_failed", error.get("code"));
        assertEquals("request body failed validation", error.get("message"));
        assertEquals("req-7", error.get("requestId"));
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) error.get("fields");
        assertEquals("name is required", fields.get("name"), "first message per field");
        assertEquals("score must be at most 100", fields.get("score"));
        assertFalse(fields.containsKey(ValidationArena.CLASS_LEVEL_FIELD),
                "class-level violations are global errors, not field errors");
    }

    private static ValidationArena.FieldVerdict row(ValidationArena.ArenaVerdict verdict, String field) {
        return verdict.perField().stream()
                .filter(candidate -> candidate.field().equals(field))
                .findFirst().orElseThrow();
    }
}
