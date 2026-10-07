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
 * ValidationTopic registration test: the topic is served, carries its nine
 * sections in curriculum order (terminal companion last), and every section
 * and block id inside it is unique (the registry itself enforces uniqueness
 * at construction — this makes the contract explicit per topic).
 */
class ValidationTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void validationTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("validation");
        assertTrue(found.isPresent(), "validation must be in the registry");
        Topic topic = found.get();
        assertEquals("Validation", topic.title());
        assertEquals(TopicGroupId.MIDDLEWARE_REQUEST, topic.group());
        assertEquals(4, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("why-validate-at-the-boundary", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("validation").orElseThrow();
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
    void choosingSectionCarriesTheInlineDemoBlock() {
        Topic topic = registry.getTopicById("validation").orElseThrow();
        ContentSection choosing = topic.sections().stream()
                .filter(section -> section.id().equals("choosing-between-them"))
                .findFirst()
                .orElseThrow();
        assertTrue(choosing.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "validation:arena".equals(demo.demoId())),
                "choosing-between-them must mount the validation:arena demo inline");
    }
}
