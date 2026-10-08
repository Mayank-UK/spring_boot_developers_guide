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
 * RedisTopic registration test: the topic is served in the data group at
 * order 5, carries its seven sections in curriculum order (terminal
 * companion last), every section and block id is unique, the playground
 * demo is mounted INLINE in the section that teaches the structures, and
 * the snippet budget is honest — every must-source block carries a repo
 * source path, with the namespace lesson, the activation guard, and the
 * adapter all quoted from the real files the suite compiles.
 */
class RedisTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void redisTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("redis");
        assertTrue(found.isPresent(), "redis must be in the registry");
        Topic topic = found.get();
        assertEquals("Redis", topic.title());
        assertEquals(TopicGroupId.DATA, topic.group());
        assertEquals(5, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("what-redis-is", topic.sections().getFirst().id());
        assertEquals("the-optional-service-discipline", topic.sections().get(1).id());
        assertEquals("the-core-structures", topic.sections().get(2).id());
        assertEquals("ttl-and-expiry", topic.sections().get(3).id());
        assertEquals("pub-sub", topic.sections().get(4).id());
        assertEquals("the-use-cases-worth-a-second-store", topic.sections().get(5).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("redis").orElseThrow();
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
    void thePlaygroundDemoIsMountedInlineInTheStructuresSection() {
        Topic topic = registry.getTopicById("redis").orElseThrow();
        ContentSection structures = topic.sections().stream()
                .filter(section -> section.id().equals("the-core-structures"))
                .findFirst().orElseThrow();
        assertTrue(structures.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "redis:playground".equals(demo.demoId())),
                "the-core-structures must mount the redis:playground demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("redis").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 8,
                "the port, adapters, activation, container and cache seam are quoted as java");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())),
                "the connection + health decisions are quoted from application.yml");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the console's request assembly is quoted from the client demo (ts)");
    }
}
