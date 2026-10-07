package guide.data.content;

import guide.content.ContentRegistry;
import guide.content.Topic;
import guide.content.TopicSummary;
import guide.domain.content.ContentRepository;
import java.util.List;
import java.util.Optional;

/**
 * Adapter: answers the domain's ContentRepository port from the typed
 * ContentRegistry. All registry knowledge stays here — the domain only sees
 * the port's contract.
 */
public class ContentRepositoryAdapter implements ContentRepository {

    private final ContentRegistry registry;

    public ContentRepositoryAdapter(ContentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<TopicSummary> findTopicSummaries() {
        return registry.getTopicSummaries();
    }

    @Override
    public Optional<Topic> findTopicById(String id) {
        return registry.getTopicById(id);
    }
}
