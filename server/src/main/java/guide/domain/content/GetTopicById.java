package guide.domain.content;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: fetch one full topic by exact id. Unknown ids come back as a
 * Failed Result carrying a not-found failure — the presentation edge decides
 * what that means on the wire (404 + error envelope).
 */
public class GetTopicById {

    private final ContentRepository repository;

    public GetTopicById(ContentRepository repository) {
        this.repository = repository;
    }

    public Result<TopicDetail> execute(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "topic id must be a non-empty string", Map.of("parameter", "id")));
        }
        return repository.findTopicById(id)
                .map(topic -> Result.<TopicDetail>success(TopicDetail.of(topic)))
                .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                        "no topic with id \"" + id + "\"", Map.of("topicId", id))));
    }
}
