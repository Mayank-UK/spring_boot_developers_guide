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
 * ApiDocumentationTopic registration test: the topic is served in the apis
 * group at order 2, carries its seven sections in curriculum order (terminal
 * companion last), every section and block id inside it is unique, and the
 * serving-swagger-ui section mounts the api:docs-link demo inline.
 */
class ApiDocumentationTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void apiDocumentationTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("api-documentation");
        assertTrue(found.isPresent(), "api-documentation must be in the registry");
        Topic topic = found.get();
        assertEquals("API documentation", topic.title());
        assertEquals(TopicGroupId.APIS, topic.group());
        assertEquals(2, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("why-machine-readable-docs", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("api-documentation").orElseThrow();
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
    void swaggerUiSectionCarriesTheInlineDemoBlock() {
        Topic topic = registry.getTopicById("api-documentation").orElseThrow();
        ContentSection serving = topic.sections().stream()
                .filter(section -> section.id().equals("serving-swagger-ui"))
                .findFirst()
                .orElseThrow();
        assertTrue(serving.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "api:docs-link".equals(demo.demoId())),
                "serving-swagger-ui must mount the api:docs-link demo inline");
    }
}
