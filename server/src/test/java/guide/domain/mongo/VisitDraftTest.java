package guide.domain.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The boundary's judge: VisitDraft.of takes what binding actually hands a
 * controller (nulls for absent fields), applies the documented defaults,
 * and names EVERY offending field in one typed answer — the exact shape
 * PostDraft.of established for the posts workbench.
 */
class VisitDraftTest {

    @Test
    void validInputBuildsAndDefaultsApply() {
        Result<VisitDraft> draft = VisitDraft.of(
                "jdbc-sql", "/topics/jdbc-sql", null, 84_000L, null);
        assertTrue(draft instanceof Success<VisitDraft>);
        VisitDraft value = ((Success<VisitDraft>) draft).value();
        assertEquals("anonymous", value.visitorToken(), "absent token defaults to anonymous");
        assertEquals("en", value.locale(), "absent locale defaults to en");
    }

    @Test
    void everyProblemIsNamedAtOnce() {
        Result<VisitDraft> draft = VisitDraft.of("JDBC!", "no-slash", "t", null, "english");
        assertTrue(draft instanceof Failed<VisitDraft>);
        Failed<VisitDraft> failed = (Failed<VisitDraft>) draft;
        assertEquals(FailureKind.VALIDATION, failed.failure().kind());
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) failed.failure().details().get("fields");
        assertEquals(true, fields.containsKey("topicId"));
        assertEquals(true, fields.containsKey("path"));
        assertEquals(true, fields.containsKey("durationMs"), "required, hence judged");
        assertEquals(true, fields.containsKey("locale"));
    }

    @Test
    void durationRangeIsJudgedNotJustPresence() {
        Result<VisitDraft> draft = VisitDraft.of(
                "jdbc-sql", "/topics/jdbc-sql", null, -5L, null);
        assertTrue(draft instanceof Failed<VisitDraft>);
        assertTrue(((Failed<VisitDraft>) draft).failure().message().contains("validation"));
    }

    @Test
    void theCompactConstructorRemainsTheLastWall() {
        assertThrows(IllegalArgumentException.class, () -> new VisitDraft(
                "JDBC", "/topics/jdbc-sql", "t", 1, "en"));
        assertThrows(IllegalArgumentException.class, () -> new VisitDraft(
                "jdbc-sql", "/topics/jdbc-sql", " ", 1, "en"),
                "a blank token cannot slip through the record itself");
    }
}
