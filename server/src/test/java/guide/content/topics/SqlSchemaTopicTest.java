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
 * SqlSchemaTopic registration test: the topic is served in the data group at
 * order 1, carries its nine sections in curriculum order (terminal companion
 * last), every section and block id is unique, both live demos are mounted
 * inline in the sections that teach them (explorer beside indexes, the uuid
 * experiment beside the key-strategy section), and the snippet budget is
 * honest — every must-source block carries a repo source path, with the SQL
 * migrations quoted as real repo files.
 */
class SqlSchemaTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void sqlSchemaTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("sql-schema");
        assertTrue(found.isPresent(), "sql-schema must be in the registry");
        Topic topic = found.get();
        assertEquals("SQL and schema design", topic.title());
        assertEquals(TopicGroupId.DATA, topic.group());
        assertEquals(1, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("relational-thinking", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("sql-schema").orElseThrow();
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
        Topic topic = registry.getTopicById("sql-schema").orElseThrow();

        ContentSection indexes = topic.sections().stream()
                .filter(section -> section.id().equals("indexes"))
                .findFirst().orElseThrow();
        assertTrue(indexes.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "db:schema-explorer".equals(demo.demoId())),
                "the indexes section must mount the db:schema-explorer demo inline");

        ContentSection keyStrategy = topic.sections().stream()
                .filter(section -> section.id().equals("key-strategy-uuidv7-vs-sequential"))
                .findFirst().orElseThrow();
        assertTrue(keyStrategy.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "db:uuid".equals(demo.demoId())),
                "the key-strategy section must mount the db:uuid demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("sql-schema").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "sql".equals(block.language())).count() >= 5,
                "the migrations are quoted as real repo SQL files");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language())).count() >= 1,
                "the flyway.enabled decision is quoted from application.yml");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "at least one snippet is quoted from the client demo (ts)");
    }
}
