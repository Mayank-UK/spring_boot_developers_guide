package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentTopics;
import guide.content.ContentSection;
import guide.content.DemoBlock;
import guide.content.SnippetMatcher;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * CachingTopic registration test: the topic is served in the
 * caching-messaging group at order 1, carries its nine sections in
 * curriculum order (terminal companion last), every section and block id is
 * unique, the caching:lab demo is mounted INLINE in the section that
 * teaches the Spring cache abstraction, and the snippet budget is honest —
 * every must-source block carries a repo source path, with the cache-aside
 * wrapper, the annotated service, the port-backed CacheManager, the HTTP
 * specimen, and the compose follow-up all quoted from the real files the
 * suite compiles.
 */
class CachingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void cachingTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("caching");
        assertTrue(found.isPresent(), "caching must be in the registry");
        Topic topic = found.get();
        assertEquals("Caching", topic.title());
        assertEquals(TopicGroupId.CACHING_MESSAGING, topic.group());
        assertEquals(1, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("two-caching-worlds", topic.sections().get(0).id());
        assertEquals("http-caching-cache-control-304", topic.sections().get(1).id());
        assertEquals("spring-cache-abstraction", topic.sections().get(2).id());
        assertEquals("cache-aside-the-pattern-under-the-annotation", topic.sections().get(3).id());
        assertEquals("invalidation-the-hard-problem", topic.sections().get(4).id());
        assertEquals("stampedes-and-dogpiles", topic.sections().get(5).id());
        assertEquals("cdns-and-hashed-filenames", topic.sections().get(6).id());
        assertEquals("what-not-to-cache", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("caching").orElseThrow();
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
    void theLabDemoIsMountedInlineInTheSpringCacheAbstractionSection() {
        Topic topic = registry.getTopicById("caching").orElseThrow();
        ContentSection abstraction = topic.sections().stream()
                .filter(section -> section.id().equals("spring-cache-abstraction"))
                .findFirst().orElseThrow();
        assertTrue(abstraction.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "caching:lab".equals(demo.demoId())),
                "spring-cache-abstraction must mount the caching:lab demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("caching").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 14, "the topic quotes at least 14 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 10,
                "both caching paths, the manager, the specimen and the use cases are quoted as java");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())),
                "the compose follow-up is quoted from the compose yaml");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the client's conditional fetch is quoted from the client demo (ts)");
        assertTrue(sourced.stream().anyMatch(block -> "nginx".equals(block.language())),
                "the edge config is quoted from nginx.conf");
        assertTrue(sourced.stream().anyMatch(block -> "dockerfile".equals(block.language())),
                "the hashed-asset ship step is quoted from the client prod Dockerfile");
    }
}
