package guide.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** One guide topic: metadata plus the full ordered section payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Topic(String id, String title, String subtitle, TopicGroupId group, int order,
        List<ContentSection> sections) {

    public Topic {
        sections = List.copyOf(sections);
    }
}
