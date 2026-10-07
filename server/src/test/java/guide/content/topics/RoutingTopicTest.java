package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RoutingTopic registration test: the topic is served in the
 * middleware-request group with its seven sections in curriculum order
 * (terminal companion last), ids are unique, the explorer demo mounts INLINE
 * in the first section, and every java/ts code block carries a source —
 * SnippetSourcesTest does the deep quoting check; this pins presence.
 */
class RoutingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void routingTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("routing");
        assertTrue(found.isPresent(), "routing must be in the registry");
        Topic topic = found.get();
        assertEquals("Routing", topic.title());
        assertEquals(TopicGroupId.MIDDLEWARE_REQUEST, topic.group());
        assertEquals(1, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("what-routing-is", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("routing").orElseThrow();
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
    void theExplorerDemoIsInlineInTheFirstSection() {
        Topic topic = registry.getTopicById("routing").orElseThrow();
        ContentSection first = topic.sections().getFirst();
        assertTrue(first.blocks().stream()
                .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                        && "routing:explorer".equals(demo.demoId())),
                "what-routing-is must mount the routing:explorer demo inline");
    }

    @Test
    void everyMustSourceBlockCarriesASource() {
        Topic topic = registry.getTopicById("routing").orElseThrow();
        topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .forEach(block -> {
                    guide.content.CodeBlock code = (guide.content.CodeBlock) block;
                    assertTrue(code.source() != null && !code.source().isBlank(),
                            () -> "block " + block.id() + " must carry a source path");
                });
    }
}
