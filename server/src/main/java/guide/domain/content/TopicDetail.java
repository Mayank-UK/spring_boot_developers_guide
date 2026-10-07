package guide.domain.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import guide.content.ContentModel;
import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/** Payload of GET /api/content/{topicId} — one full topic, versioned. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicDetail(int version, String id, String title, String subtitle, TopicGroupId group,
        int order, List<ContentSection> sections) {

    public TopicDetail {
        sections = List.copyOf(sections);
    }

    public static TopicDetail of(Topic topic) {
        return new TopicDetail(ContentModel.CONTENT_API_VERSION, topic.id(), topic.title(),
                topic.subtitle(), topic.group(), topic.order(), topic.sections());
    }
}
