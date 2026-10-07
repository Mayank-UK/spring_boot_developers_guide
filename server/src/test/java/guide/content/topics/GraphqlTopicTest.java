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
 * GraphqlTopic registration test: the topic is served, carries its eight
 * sections in curriculum order (terminal companion last), and every section
 * and block id inside it is unique. Also pins the inline demo contract: the
 * introspection-and-tooling section mounts api:graphql-playground.
 */
class GraphqlTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void graphqlTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("graphql");
        assertTrue(found.isPresent(), "graphql must be in the registry");
        Topic topic = found.get();
        assertEquals("GraphQL", topic.title());
        assertEquals(TopicGroupId.APIS, topic.group());
        assertEquals(3, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("the-problem-graphql-solves", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("graphql").orElseThrow();
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
    void introspectionSectionCarriesTheInlineDemoBlock() {
        Topic topic = registry.getTopicById("graphql").orElseThrow();
        ContentSection introspection = topic.sections().stream()
                .filter(section -> section.id().equals("introspection-and-tooling"))
                .findFirst()
                .orElseThrow();
        assertTrue(introspection.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "api:graphql-playground".equals(demo.demoId())),
                "introspection-and-tooling must mount the api:graphql-playground demo inline");
    }
}
