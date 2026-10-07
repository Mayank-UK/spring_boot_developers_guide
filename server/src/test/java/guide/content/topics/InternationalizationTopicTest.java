package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.DemoBlock;
import guide.content.SnippetMatcher;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * InternationalizationTopic registration test: the topic is served in the
 * internationalization group at order 1, carries its SEVEN sections in
 * curriculum order (terminal companion last), every section and block id
 * is unique, the negotiator demo is mounted INLINE in the negotiation
 * section that teaches it, and the snippet budget is honest — every
 * must-source block carries a repo source path, with the java/properties/
 * yaml/ts families all quoted from real repo files.
 */
class InternationalizationTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void internationalizationTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("internationalization");
        assertTrue(found.isPresent(), "internationalization must be in the registry");
        Topic topic = found.get();
        assertEquals("Internationalization", topic.title());
        assertEquals(TopicGroupId.INTERNATIONALIZATION, topic.group());
        assertEquals(1, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("global-by-default", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("internationalization").orElseThrow();
        List<String> sectionIds = topic.sections().stream().map(ContentSection::id).toList();
        assertEquals(sectionIds.size(), sectionIds.stream().distinct().count(),
                "section ids must be unique");
        List<String> blockIds = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .map(block -> block.id())
                .toList();
        assertEquals(blockIds.size(), blockIds.stream().distinct().count(),
                "block ids must be unique across the topic");
    }

    @Test
    void theNegotiatorDemoIsMountedInlineInTheNegotiationSection() {
        Topic topic = registry.getTopicById("internationalization").orElseThrow();
        ContentSection negotiation = topic.sections().stream()
                .filter(section -> section.id().equals("accept-language-negotiation"))
                .findFirst().orElseThrow();
        assertTrue(negotiation.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "i18n:negotiator".equals(demo.demoId())),
                "the negotiation section must mount the i18n:negotiator demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("internationalization").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 5,
                "the parser, negotiator, resolver, probe and controller are quoted as java");
        assertTrue(sourced.stream().filter(block -> "properties".equals(block.language())).count() >= 4,
                "the base/en-adjacent, ja, fr and pseudo bundle lines are quoted as properties");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())),
                "the flagged application.yml append is quoted as yaml");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the client demo's header builder is quoted as ts");
    }
}
