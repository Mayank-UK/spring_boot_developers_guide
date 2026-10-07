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
 * HttpWebTopic registration test: the topic is served, carries its nine
 * sections in curriculum order (terminal companion last), and every section
 * and block id inside it is unique (the registry itself enforces uniqueness
 * at construction — this makes the contract explicit per topic).
 */
class HttpWebTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void httpWebTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("http-web");
        assertTrue(found.isPresent(), "http-web must be in the registry");
        Topic topic = found.get();
        assertEquals("HTTP and Web Fundamentals", topic.title());
        assertEquals(TopicGroupId.FUNDAMENTALS, topic.group());
        assertEquals(3, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("the-request-journey", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("http-web").orElseThrow();
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
    void methodsSectionCarriesTheInlineDemoBlock() {
        Topic topic = registry.getTopicById("http-web").orElseThrow();
        ContentSection methods = topic.sections().stream()
                .filter(section -> section.id().equals("methods-status-headers"))
                .findFirst()
                .orElseThrow();
        assertTrue(methods.blocks().stream()
                .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                        && "http:headers".equals(demo.demoId())),
                "methods-status-headers must mount the http:headers demo inline");
    }
}
