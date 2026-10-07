package guide.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Names a client-side live demo component bound to real server endpoints.
 * The demoId (not the block id) is the client's lookup key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DemoBlock(String id, String demoId, String title, String description) implements ContentBlock {

    @JsonProperty("kind")
    @Override
    public String kind() {
        return "demo";
    }
}
