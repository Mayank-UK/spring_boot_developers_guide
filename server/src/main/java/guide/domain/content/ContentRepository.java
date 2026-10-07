package guide.domain.content;

import guide.content.Topic;
import guide.content.TopicSummary;
import java.util.List;
import java.util.Optional;

/**
 * The topic-catalog PORT: the registry access the content use cases need,
 * expressed as an interface the domain owns. The content registry itself is
 * infrastructure (it loads every topic at construction) — this file never
 * imports it. The composition root supplies the adapter; tests may hand in
 * fakes. Same lesson as every repository: the domain names what it needs,
 * wiring decides what answers.
 */
public interface ContentRepository {

    /** Summaries for list views — never carries the sections payload. */
    List<TopicSummary> findTopicSummaries();

    /** Exact-match lookup; empty when no topic carries that id. */
    Optional<Topic> findTopicById(String id);
}
