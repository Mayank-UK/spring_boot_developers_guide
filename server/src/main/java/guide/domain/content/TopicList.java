package guide.domain.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import guide.content.ContentModel;
import guide.content.TopicGroupId;
import guide.content.TopicSummary;
import java.util.Arrays;
import java.util.List;

/** Payload of GET /api/content — the nav tree without section payloads. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicList(int version, List<TopicGroup> groups, List<TopicSummary> topics) {

    public TopicList {
        groups = List.copyOf(groups);
        topics = List.copyOf(topics);
    }

    public static TopicList of(List<TopicSummary> topics) {
        List<TopicGroup> groups = Arrays.stream(TopicGroupId.values())
                .map(group -> new TopicGroup(group.wireName(), group.title(), group.order()))
                .toList();
        return new TopicList(ContentModel.CONTENT_API_VERSION, groups, topics);
    }

    /** One nav group — id, title, and display order, from the real group enum. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopicGroup(String id, String title, int order) {
    }
}
