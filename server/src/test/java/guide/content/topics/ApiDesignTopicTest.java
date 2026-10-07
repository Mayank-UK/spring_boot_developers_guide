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
 * ApiDesignTopic registration test: the topic is served in the apis group at
 * order 1, carries its eight sections in curriculum order (terminal
 * companion last), every section and block id inside it is unique, and the
 * whole-pipeline section mounts the api:posts-workbench demo inline.
 */
class ApiDesignTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void apiDesignTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("api-design");
        assertTrue(found.isPresent(), "api-design must be in the registry");
        Topic topic = found.get();
        assertEquals("API design", topic.title());
        assertEquals(TopicGroupId.APIS, topic.group());
        assertEquals(1, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("what-rest-actually-is", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("api-design").orElseThrow();
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
    void pipelineSectionCarriesTheInlineDemoBlock() {
        Topic topic = registry.getTopicById("api-design").orElseThrow();
        ContentSection pipeline = topic.sections().stream()
                .filter(section -> section.id().equals("the-whole-pipeline"))
                .findFirst()
                .orElseThrow();
        assertTrue(pipeline.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "api:posts-workbench".equals(demo.demoId())),
                "the-whole-pipeline must mount the api:posts-workbench demo inline");
    }
}
