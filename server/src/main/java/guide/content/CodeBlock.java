package guide.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A code snippet. {@code source}, when present, is the repo-root-relative
 * path of the real file this snippet is quoted from (e.g.
 * 'server/src/main/java/guide/GuideApplication.java') — THE CODE IS THE SOURCE
 * OF TRUTH: teaching snippets are quoted from real repo source, never
 * hand-invented. SnippetSourcesTest enforces the quote mechanically: the
 * snippet must be an ordered subsequence of the file's lines after
 * comment/blank normalization; must-source languages without a source fail
 * the suite (ratchet = 0). Bash/command/output blocks are exempt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeBlock(String id, String language, String caption, String code, String source)
        implements ContentBlock {

    @JsonProperty("kind")
    @Override
    public String kind() {
        return "code";
    }
}
