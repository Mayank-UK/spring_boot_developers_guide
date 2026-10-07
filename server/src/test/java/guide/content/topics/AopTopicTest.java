package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentBlock;
import guide.content.ContentRegistry;
import guide.content.ContentTopics;
import guide.content.DemoBlock;
import guide.content.TextBlock;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Topic 06's own contract: present in the registry, fundamentals group in
 * curriculum order 6, its nine sections with unique ids, sourced code blocks
 * everywhere a must-source language appears, the three demos mounted inline
 * in their teaching sections (never queued at the end), and comprehensive
 * prose depth.
 */
class AopTopicTest {

    private static final ContentRegistry REGISTRY = new ContentRegistry(ContentTopics.all());

    private static Topic topic() {
        return REGISTRY.getTopicById("aop").orElseThrow(
                () -> new AssertionError("aop must be registered"));
    }

    @Test
    void topicIsRegisteredWithMetadata() {
        Topic topic = topic();
        assertEquals("Aspect-Oriented Programming", topic.title());
        assertEquals(TopicGroupId.FUNDAMENTALS, topic.group());
        assertEquals(6, topic.order());
    }

    @Test
    void hasExactlyTheNineCurriculumSectionsInOrder() {
        List<String> ids = topic().sections().stream().map(section -> section.id()).toList();
        assertEquals(List.of(
                "the-cross-cutting-problem",
                "proxies-jdk-vs-cglib",
                "aspect-anatomy",
                "pointcut-expressions",
                "aspects-everywhere",
                "the-self-invocation-trap",
                "aspect-ordering",
                "when-not-to-aop",
                "terminal-companion"), ids);
        assertEquals("terminal-companion", topic().sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUnique() {
        Set<String> sectionIds = new HashSet<>();
        Set<String> blockIds = new HashSet<>();
        for (var section : topic().sections()) {
            assertTrue(sectionIds.add(section.id()), "duplicate section id " + section.id());
            for (ContentBlock block : section.blocks()) {
                assertTrue(blockIds.add(block.id()), "duplicate block id " + block.id());
            }
        }
    }

    @Test
    void everyNonBashCodeBlockCarriesARealSource() {
        int sourced = 0;
        for (var section : topic().sections()) {
            for (ContentBlock block : section.blocks()) {
                if (block instanceof CodeBlock code && !"bash".equals(code.language())) {
                    assertNotNull(code.source(), code.id() + " must name its source");
                    sourced++;
                }
            }
        }
        assertTrue(sourced >= 10, "expected a comprehensive topic, saw only " + sourced + " sourced blocks");
    }

    @Test
    void theThreeDemosAreMountedInlineInTheirTeachingSections() {
        assertEquals("aop:proxy", demoInSection("proxies-jdk-vs-cglib").demoId());
        assertEquals("aop:trace", demoInSection("aspect-anatomy").demoId());
        assertEquals("aop:self-invocation", demoInSection("the-self-invocation-trap").demoId());
    }

    private static DemoBlock demoInSection(String sectionId) {
        var section = topic().sections().stream()
                .filter(candidate -> candidate.id().equals(sectionId))
                .findFirst().orElseThrow();
        return section.blocks().stream()
                .filter(block -> block instanceof DemoBlock)
                .map(block -> (DemoBlock) block)
                .findFirst().orElseThrow();
    }

    @Test
    void topicCarriesSubstantialProse() {
        int words = topic().sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).body())
                .mapToInt(body -> body.split("\\s+").length)
                .sum();
        assertTrue(words >= 2500 && words <= 3500,
                "topic prose out of range (2500–3500 words): " + words);
    }
}
