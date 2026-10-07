package guide.content;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A topic without its sections payload — what list endpoints serve. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicSummary(String id, String title, String subtitle, TopicGroupId group, int order,
        int sectionCount) {

    public static TopicSummary from(Topic topic) {
        return new TopicSummary(topic.id(), topic.title(), topic.subtitle(), topic.group(),
                topic.order(), topic.sections().size());
    }
}
