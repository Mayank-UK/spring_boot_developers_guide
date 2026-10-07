package guide.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Prose. May contain \n\n paragraph breaks — the client renders it pre-line. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TextBlock(String id, String body) implements ContentBlock {

    @JsonProperty("kind")
    @Override
    public String kind() {
        return "text";
    }
}
