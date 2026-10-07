package guide.content.topics;

import guide.content.CodeBlock;
import guide.content.ContentBlock;
import guide.content.ContentSection;
import guide.content.DemoBlock;
import guide.content.TextBlock;
import java.util.List;

/**
 * The topic-authoring API: tiny static factories so topic files read as an
 * outline instead of constructor noise. Every Phase 3+ topic file builds its
 * tree with these and hands the result to ContentTopics.all().
 *
 * One rule travels with code(): every must-source language block (java, xml,
 * yaml, ts, json, sql, …) MUST carry a repo-root-relative source path that
 * passes SnippetSourcesTest — write the real implementation first, then
 * quote it. Only bash blocks are exempt.
 */
public final class TopicBlocks {

    private TopicBlocks() {
    }

    /** Prose block; blank lines separate paragraphs (the client renders pre-line). */
    public static TextBlock text(String id, String body) {
        return new TextBlock(id, body);
    }

    /**
     * Sourced code block. {@code source} is the repo-root-relative path of
     * the real file this snippet is quoted from; the snippet must be an
     * ordered subsequence of that file (elide with {@code // …} lines).
     */
    public static CodeBlock code(String id, String language, String caption, String source, String code) {
        return new CodeBlock(id, language, caption, code, source);
    }

    /** Live-demo pointer; {@code demoId} is the client registry's lookup key. */
    public static DemoBlock demo(String id, String demoId, String title, String description) {
        return new DemoBlock(id, demoId, title, description);
    }

    /** One teaching section: id, title, optional lead (null for none), ordered blocks. */
    public static ContentSection section(String id, String title, String lead, ContentBlock... blocks) {
        return new ContentSection(id, title, lead, List.of(blocks));
    }
}
