package guide.domain.mongo;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A visit at birth — everything the API caller provides, before identity
 * (id), time (occurredAt), and pseudonymization (visitorHash) are assigned
 * by the store. The compact constructor re-enforces the same rules
 * VisitDocument will check again, so an invalid draft cannot exist long
 * enough to be handed anywhere (topic 10's hand-rolled validation stance,
 * applied to event-shaped data).
 *
 * The {@link #of} factory is the BOUNDARY'S judge: binding leaves absent
 * JSON fields null, so the factory takes nullable input, applies the
 * documented defaults (visitorToken "anonymous", locale "en"), and answers
 * every problem at once as a typed Failure naming the fields — the exact
 * shape PostDraft.of established for the posts workbench.
 */
public record VisitDraft(String topicId, String path, String visitorToken,
        long durationMs, String locale) {

    public VisitDraft {
        String problem = VisitDocument.topicIdProblem(topicId);
        if (problem != null) {
            throw new IllegalArgumentException("visit topicId " + problem);
        }
        problem = VisitDocument.pathProblem(path);
        if (problem != null) {
            throw new IllegalArgumentException("visit path " + problem);
        }
        if (visitorToken == null || visitorToken.isBlank()) {
            throw new IllegalArgumentException("visit visitorToken must be a non-blank string");
        }
        problem = VisitDocument.durationProblem(durationMs);
        if (problem != null) {
            throw new IllegalArgumentException("visit durationMs " + problem);
        }
        problem = VisitDocument.localeProblem(locale);
        if (problem != null) {
            throw new IllegalArgumentException("visit locale " + problem);
        }
    }

    /** Judges nullable request input; absent optional fields take their documented defaults. */
    public static Result<VisitDraft> of(String topicId, String path, String visitorToken,
            Long durationMs, String locale) {
        Map<String, String> fields = new LinkedHashMap<>();
        String fault = VisitDocument.topicIdProblem(topicId);
        if (fault != null) {
            fields.put("topicId", fault);
        }
        fault = VisitDocument.pathProblem(path);
        if (fault != null) {
            fields.put("path", fault);
        }
        if (durationMs == null) {
            fields.put("durationMs", "is required");
        } else {
            fault = VisitDocument.durationProblem(durationMs);
            if (fault != null) {
                fields.put("durationMs", fault);
            }
        }
        if (locale != null && !locale.isBlank()) {
            fault = VisitDocument.localeProblem(locale.strip());
            if (fault != null) {
                fields.put("locale", fault);
            }
        }
        if (!fields.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "request body failed validation", Map.of("fields", fields)));
        }
        return Result.success(new VisitDraft(
                topicId, path,
                visitorToken == null || visitorToken.isBlank() ? "anonymous" : visitorToken.strip(),
                durationMs,
                locale == null || locale.isBlank() ? "en" : locale.strip()));
    }
}

