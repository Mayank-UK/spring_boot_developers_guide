package guide.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** One teaching section: concept lead, then its ordered blocks. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentSection(String id, String title, String lead, List<ContentBlock> blocks) {

    public ContentSection {
        blocks = List.copyOf(blocks);
    }
}
