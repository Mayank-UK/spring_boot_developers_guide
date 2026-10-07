package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The creation single source of truth: everything required to create (or
 * wholesale replace) a post, before identity and timestamps exist. Two doors,
 * one rulebook:
 *
 * - The CANONICAL CONSTRUCTOR enforces the invariants and throws on breach —
 *   the domain door no internal caller can sneak past (defense in depth; the
 *   entity applies the same rules again).
 * - {@link #of} is the polite front desk for the HTTP edge: the same rules,
 *   judged into a Failed Result carrying per-field reasons, so a hostile body
 *   becomes a 422 envelope instead of a stack trace.
 *
 * The rules themselves live in the static *Problem helpers so the entity can
 * enforce exactly the same ones without copying the strings.
 */
public record PostDraft(String title, String body, List<String> tags, PostStatus status) {

    /** Titles are 1..120 characters after trimming (a whitespace-only title is absent). */
    static final int TITLE_MAX = 120;

    /** Bodies are at most 64 KiB measured in CHARACTERS (UTF-8 byte length would be encoding-dependent). */
    static final int BODY_MAX = 64 * 1024;

    public PostDraft {
        String titleFault = titleProblem(title);
        if (titleFault != null) {
            throw new IllegalArgumentException("post title " + titleFault);
        }
        String bodyFault = bodyProblem(body);
        if (bodyFault != null) {
            throw new IllegalArgumentException("post body " + bodyFault);
        }
        title = title.strip();
        body = body.strip();
        tags = normalizeTags(tags);
        status = Objects.requireNonNull(status, "status");
    }

    /** Wire-facing factory: judges (title, body, tags, status-as-string) into a Result, never throws. */
    public static Result<PostDraft> of(String title, String body, List<String> tags, String statusWire) {
        Map<String, String> fields = new LinkedHashMap<>();
        String titleFault = titleProblem(title);
        if (titleFault != null) {
            fields.put("title", titleFault);
        }
        String bodyFault = bodyProblem(body);
        if (bodyFault != null) {
            fields.put("body", bodyFault);
        }
        PostStatus status = DRAFT_DEFAULT;
        if (statusWire != null && !statusWire.isBlank()) {
            try {
                status = PostStatus.fromWire(statusWire.strip());
            } catch (IllegalArgumentException e) {
                fields.put("status", "must be \"draft\" or \"published\"");
            }
        }
        for (int i = 0; tags != null && i < tags.size(); i++) {
            if (tags.get(i) == null || tags.get(i).isBlank()) {
                fields.put("tags", "every tag must be a non-blank string");
                break;
            }
        }
        if (!fields.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "request body failed validation", Map.of("fields", fields)));
        }
        return Result.success(new PostDraft(title, body, tags, status));
    }

    /** A creation request that omits (or blanks) status means draft — drafts are the safe default. */
    private static final PostStatus DRAFT_DEFAULT = PostStatus.DRAFT;

    /** null when the title is acceptable, the reason when it is not. */
    static String titleProblem(String title) {
        if (title == null || title.isBlank()) {
            return "is required";
        }
        if (title.strip().length() > TITLE_MAX) {
            return "must be at most " + TITLE_MAX + " characters";
        }
        return null;
    }

    /** null when the body is acceptable (it may be empty — drafts start empty), the reason when it is not. */
    static String bodyProblem(String body) {
        if (body == null) {
            return "is required";
        }
        if (body.length() > BODY_MAX) {
            return "must be at most " + BODY_MAX + " characters (64 KiB)";
        }
        return null;
    }

    /** Copies, trims, drops blanks, removes duplicates — order of first appearance kept. */
    static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            String trimmed = tag == null ? "" : tag.strip();
            if (!trimmed.isEmpty() && !normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }
}
