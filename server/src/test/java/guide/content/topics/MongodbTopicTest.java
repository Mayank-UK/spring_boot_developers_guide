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
 * MongodbTopic registration test: the topic is served in the data group at
 * order 4, carries its TEN sections in curriculum order (terminal companion
 * last), every section and block id is unique, the console demo is mounted
 * INLINE in the queries section that teaches it, and the snippet budget is
 * honest — every must-source block carries a repo source path, with the
 * java/yaml/ts/xml families all quoted from real repo files.
 */
class MongodbTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void mongodbTopicIsRegisteredWithItsTenSections() {
        Optional<Topic> found = registry.getTopicById("mongodb");
        assertTrue(found.isPresent(), "mongodb must be in the registry");
        Topic topic = found.get();
        assertEquals("MongoDB and Spring Data", topic.title());
        assertEquals(TopicGroupId.DATA, topic.group());
        assertEquals(4, topic.order());
        assertEquals(10, topic.sections().size());
        assertEquals("document-thinking", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("mongodb").orElseThrow();
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
    void theConsoleDemoIsMountedInlineInTheQueriesSection() {
        Topic topic = registry.getTopicById("mongodb").orElseThrow();
        ContentSection queries = topic.sections().stream()
                .filter(section -> section.id().equals("queries-and-the-criteria-api"))
                .findFirst().orElseThrow();
        assertTrue(queries.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "db:mongo-console".equals(demo.demoId())),
                "the queries section must mount the db:mongo-console demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("mongodb").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 6,
                "the adapters, guard, domain record and controller are quoted as java");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language())).count() >= 2,
                "the connection URI + health flag and the compose service are quoted as yaml");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "at least one snippet is quoted from the client demo (ts)");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())),
                "the pom's flagged coordinates are quoted as xml");
    }
}
