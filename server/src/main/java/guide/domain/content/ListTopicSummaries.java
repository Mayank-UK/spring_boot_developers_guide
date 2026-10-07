package guide.domain.content;

import guide.core.result.Result;

/**
 * Use case: the topic list for navigation. Pure and synchronous — the
 * registry is validated at construction, so listing only decides between
 * success and a typed failure (and in practice always succeeds).
 */
public class ListTopicSummaries {

    private final ContentRepository repository;

    public ListTopicSummaries(ContentRepository repository) {
        this.repository = repository;
    }

    public Result<TopicList> execute() {
        return Result.success(TopicList.of(repository.findTopicSummaries()));
    }
}
