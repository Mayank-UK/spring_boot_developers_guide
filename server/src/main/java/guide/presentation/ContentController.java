package guide.presentation;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.content.GetTopicById;
import guide.domain.content.ListTopicSummaries;
import guide.domain.content.TopicDetail;
import guide.domain.content.TopicList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content API — the typed, versioned topic tree the React client renders:
 * - GET /api/content            → { version, groups, topics (summaries) }
 * - GET /api/content/{topicId}  → { version, ...topic }
 *
 * Thin by contract: parse the input, call ONE use case, map the Result to an
 * HTTP response. A Failed Result becomes the typed exception matching its
 * failure kind; the global handler advice renders the envelope.
 *
 * OpenAPI curation (topic 12): a tag for the group plus one summary per
 * operation — the typed return records (TopicList, TopicDetail) already give
 * the generator every schema field it needs, which is exactly why no
 * @Schema annotations are required here.
 */
@Tag(name = "Content", description = "The typed topic tree the React client renders — the guide itself.")
@RestController
public class ContentController {

    private final ListTopicSummaries listTopicSummaries;
    private final GetTopicById getTopicById;

    public ContentController(ListTopicSummaries listTopicSummaries, GetTopicById getTopicById) {
        this.listTopicSummaries = listTopicSummaries;
        this.getTopicById = getTopicById;
    }

    /** GET /api/content → 200 { version, groups, topics } */
    @Operation(summary = "List every topic summary, grouped and ordered",
            operationId = "listTopics",
            description = "The guide's table of contents: groups in curriculum order, each with "
                    + "its topics' ids, titles, and section counts.")
    @GetMapping("/api/content")
    TopicList list() {
        return unwrap(listTopicSummaries.execute());
    }

    /** GET /api/content/{topicId} → 200 { version, ...topic } or 404 envelope */
    @Operation(summary = "One topic's full section/block tree",
            operationId = "getTopicById",
            description = "Sections in order, each carrying its text, code, and demo blocks — "
                    + "what TopicView renders.")
    @GetMapping("/api/content/{topicId}")
    TopicDetail detail(@PathVariable String topicId) {
        return unwrap(getTopicById.execute(topicId));
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
