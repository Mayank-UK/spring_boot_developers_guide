package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.DemoBlock;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RequestResponseTopic registration test: the topic is served in the
 * middleware-request group at curriculum order 3, carries its eight
 * sections in order (terminal companion last), every section and block id
 * is unique, and both live demos (reqres:inspector, reqres:methods) are
 * mounted INLINE in their teaching sections — never queued at the end.
 */
class RequestResponseTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void requestResponseTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("request-response");
        assertTrue(found.isPresent(), "request-response must be in the registry");
        Topic topic = found.get();
        assertEquals("Requests and Responses in Spring MVC", topic.title());
        assertEquals(TopicGroupId.MIDDLEWARE_REQUEST, topic.group());
        assertEquals(3, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("the-request-object", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("request-response").orElseThrow();
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
    void bothDemosAreMountedInlineInTheirTeachingSections() {
        Topic topic = registry.getTopicById("request-response").orElseThrow();
        assertTrue(sectionBlock(topic, "the-request-object", "reqres:inspector"),
                "the-request-object must mount the reqres:inspector demo inline");
        assertTrue(sectionBlock(topic, "responseentity-and-status", "reqres:methods"),
                "responseentity-and-status must mount the reqres:methods demo inline");
        assertTrue(topic.sections().getLast().blocks().stream()
                        .noneMatch(block -> block instanceof DemoBlock),
                "the terminal companion carries no demo — demos are inline only");
    }

    private static boolean sectionBlock(Topic topic, String sectionId, String demoId) {
        return topic.sections().stream()
                .filter(section -> section.id().equals(sectionId))
                .findFirst().orElseThrow()
                .blocks().stream()
                .anyMatch(block -> block instanceof DemoBlock demo && demoId.equals(demo.demoId()));
    }
}
