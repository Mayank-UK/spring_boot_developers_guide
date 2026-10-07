package guide.domain.posts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PostDraft contract: the polite factory judges a hostile wire payload into
 * per-field reasons (a Failed Result, never a throw), the compact constructor
 * is the throwing door behind it, and both enforce the SAME rulebook — title
 * 1..120, body present but possibly empty and at most 64 KiB, tags normalized.
 */
class PostDraftTest {

    @Test
    void happyPathTrimsTitleNormalizesTagsAndDefaultsToDraft() {
        Result<PostDraft> result = PostDraft.of(
                "  Nouns, not verbs  ", "  Every URL is a noun.  ",
                List.of("rest", " rest ", "rest"), null);

        assertTrue(result instanceof Success<PostDraft>, "a valid payload must succeed");
        PostDraft draft = ((Success<PostDraft>) result).value();
        assertEquals("Nouns, not verbs", draft.title());
        assertEquals("Every URL is a noun.", draft.body());
        assertEquals(List.of("rest"), draft.tags(), "tags are trimmed and duplicates removed");
        assertEquals(PostStatus.DRAFT, draft.status(), "an omitted status means draft");
    }

    @Test
    void missingTitleAndBodyArePerFieldReasonsNeverThrows() {
        Result<PostDraft> result = PostDraft.of(null, null, null, null);

        assertTrue(result instanceof Failed<PostDraft>);
        Failed<PostDraft> failed = (Failed<PostDraft>) result;
        assertEquals(FailureKind.VALIDATION, failed.failure().kind());
        assertNotNull(failed.failure().details().get("fields"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> fields = (java.util.Map<String, String>)
                failed.failure().details().get("fields");
        assertEquals("is required", fields.get("title"));
        assertEquals("is required", fields.get("body"));
    }

    @Test
    void overlongTitleIsJudgedWithTheBoundInTheMessage() {
        String title121 = "x".repeat(121);
        Result<PostDraft> result = PostDraft.of(title121, "body", null, null);

        assertTrue(result instanceof Failed<PostDraft>);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> fields = (java.util.Map<String, String>)
                ((Failed<PostDraft>) result).failure().details().get("fields");
        assertEquals("must be at most 120 characters", fields.get("title"));
    }

    @Test
    void bodyBoundaryIsExactly64KiBInCharacters() {
        String body64k = "y".repeat(64 * 1024);
        assertTrue(PostDraft.of("t", body64k, null, null) instanceof Success<PostDraft>,
                "exactly 64 KiB is acceptable");

        String bodyTooBig = "y".repeat(64 * 1024 + 1);
        Result<PostDraft> over = PostDraft.of("t", bodyTooBig, null, null);
        assertTrue(over instanceof Failed<PostDraft>);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> fields = (java.util.Map<String, String>)
                ((Failed<PostDraft>) over).failure().details().get("fields");
        assertEquals("must be at most 65536 characters (64 KiB)", fields.get("body"));
    }

    @Test
    void unknownStatusAndBlankTagsArePerFieldReasons() {
        Result<PostDraft> result = PostDraft.of("t", "b", List.of("ok", "  "), "archived");

        assertTrue(result instanceof Failed<PostDraft>);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> fields = (java.util.Map<String, String>)
                ((Failed<PostDraft>) result).failure().details().get("fields");
        assertEquals("must be \"draft\" or \"published\"", fields.get("status"));
        assertEquals("every tag must be a non-blank string", fields.get("tags"));
    }

    @Test
    void compactConstructorIsTheThrowingDoorForInternalCallers() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostDraft(null, "b", null, PostStatus.DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> new PostDraft("x".repeat(121), "b", null, PostStatus.DRAFT));
        assertThrows(NullPointerException.class,
                () -> new PostDraft("t", "b", null, null));
        // An empty body is legal: drafts start empty.
        assertEquals("", new PostDraft("t", "", null, PostStatus.DRAFT).body());
    }

    @Test
    void titleBoundariesAreOneAndOneHundredTwenty() {
        assertEquals("x", new PostDraft(" x ", "", null, PostStatus.DRAFT).title());
        assertEquals("x".repeat(120), new PostDraft("x".repeat(120), "", null, PostStatus.DRAFT).title());
    }
}
