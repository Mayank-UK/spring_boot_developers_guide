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
 * GrpcTopic registration test: the topic is served, carries its eight
 * sections in curriculum order (terminal companion last), and every section
 * and block id inside it is unique (the registry enforces uniqueness at
 * construction — this makes the contract explicit per topic).
 */
class GrpcTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void grpcTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("grpc");
        assertTrue(found.isPresent(), "grpc must be in the registry");
        Topic topic = found.get();
        assertEquals("gRPC", topic.title());
        assertEquals(TopicGroupId.APIS, topic.group());
        assertEquals(5, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("what-grpc-is", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("grpc").orElseThrow();
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
    void stubsSectionCarriesTheInlineDemoBlock() {
        Topic topic = registry.getTopicById("grpc").orElseThrow();
        ContentSection stubs = topic.sections().stream()
                .filter(section -> section.id().equals("calling-grpc-stubs-and-channels"))
                .findFirst()
                .orElseThrow();
        assertTrue(stubs.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "api:grpc-console".equals(demo.demoId())),
                "calling-grpc-stubs-and-channels must mount the api:grpc-console demo inline");
    }
}
