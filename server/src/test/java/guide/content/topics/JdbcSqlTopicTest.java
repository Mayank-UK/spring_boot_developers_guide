package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.ContentBlock;
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
 * JdbcSqlTopic registration test: the topic is served in the data group at
 * order 2, carries its TWELVE sections in curriculum order (terminal
 * companion last), every section and block id is unique, and each of the six
 * demos is mounted INLINE inside the section that teaches it — the teaching
 * anatomy invariant (a demo beside its concept, never queued at page end).
 */
class JdbcSqlTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void jdbcSqlTopicIsRegisteredWithItsTwelveSections() {
        Optional<Topic> found = registry.getTopicById("jdbc-sql");
        assertTrue(found.isPresent(), "jdbc-sql must be in the registry");
        Topic topic = found.get();
        assertEquals("SQL with JDBC and JdbcTemplate", topic.title());
        assertEquals(TopicGroupId.DATA, topic.group());
        assertEquals(2, topic.order());
        assertEquals(12, topic.sections().size());
        assertEquals("drivers-the-layers", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("jdbc-sql").orElseThrow();
        List<String> sectionIds = topic.sections().stream().map(ContentSection::id).toList();
        assertEquals(sectionIds.size(), sectionIds.stream().distinct().count(),
                "section ids must be unique");
        List<String> blockIds = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .map(ContentBlock::id)
                .toList();
        assertEquals(blockIds.size(), blockIds.stream().distinct().count(),
                "block ids must be unique across the topic");
    }

    @Test
    void everyDemoIsMountedInlineInItsTeachingSection() {
        Topic topic = registry.getTopicById("jdbc-sql").orElseThrow();
        assertEquals(6, demoIds(topic).size(), "the six db demos are all mounted");
        assertDemoLivesIn(topic, "db:sql-safety", "parameterized-queries-or-else");
        assertDemoLivesIn(topic, "db:migrations", "migrations-with-flyway");
        assertDemoLivesIn(topic, "db:sql-lab", "sql-by-hand");
        assertDemoLivesIn(topic, "db:search", "full-text-search");
        assertDemoLivesIn(topic, "db:race", "isolation-locking-and-the-race");
        assertDemoLivesIn(topic, "db:audit", "audit-logging-in-transaction");
    }

    private static List<String> demoIds(Topic topic) {
        return topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(DemoBlock.class::isInstance)
                .map(block -> ((DemoBlock) block).demoId())
                .toList();
    }

    private static void assertDemoLivesIn(Topic topic, String demoId, String sectionId) {
        boolean mounted = topic.sections().stream()
                .filter(section -> section.id().equals(sectionId))
                .findFirst().orElseThrow()
                .blocks().stream()
                .filter(DemoBlock.class::isInstance)
                .map(block -> ((DemoBlock) block).demoId())
                .anyMatch(demoId::equals);
        assertTrue(mounted, demoId + " must be inline inside " + sectionId);
    }
}
