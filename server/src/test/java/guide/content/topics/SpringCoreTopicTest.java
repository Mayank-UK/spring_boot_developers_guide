package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentBlock;
import guide.content.ContentRegistry;
import guide.content.ContentTopics;
import guide.content.DemoBlock;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Topic 05's own contract: present in the registry, fundamentals group in
 * curriculum order, its twelve sections with unique ids, sourced code blocks
 * everywhere a must-source language appears, and the core:container demo
 * mounted INLINE in the application-context section (never queued at the
 * end).
 */
class SpringCoreTopicTest {

    private static final ContentRegistry REGISTRY = new ContentRegistry(ContentTopics.all());

    private static Topic topic() {
        return REGISTRY.getTopicById("spring-core").orElseThrow(
                () -> new AssertionError("spring-core must be registered"));
    }

    @Test
    void topicIsRegisteredWithMetadata() {
        Topic topic = topic();
        assertEquals("Spring Core: The IoC Container", topic.title());
        assertEquals(TopicGroupId.FUNDAMENTALS, topic.group());
        assertEquals(5, topic.order());
    }

    @Test
    void hasExactlyTheTwelveCurriculumSectionsInOrder() {
        List<String> ids = topic().sections().stream().map(section -> section.id()).toList();
        assertEquals(List.of(
                "why-ioc",
                "application-context",
                "bean-definitions",
                "injection-styles",
                "wiring-ambiguity",
                "bean-lifecycle",
                "scopes",
                "profiles-and-conditionals",
                "application-events",
                "spel-and-value",
                "circular-dependencies",
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
        assertTrue(sourced >= 12, "expected a comprehensive topic, saw only " + sourced + " sourced blocks");
    }

    @Test
    void demoIsMountedInlineInTheApplicationContextSection() {
        var contextSection = topic().sections().stream()
                .filter(section -> section.id().equals("application-context"))
                .findFirst().orElseThrow();
        DemoBlock demo = contextSection.blocks().stream()
                .filter(block -> block instanceof DemoBlock)
                .map(block -> (DemoBlock) block)
                .findFirst().orElseThrow();
        assertEquals("core:container", demo.demoId());
    }

    @Test
    void topicCarriesSubstantialProse() {
        int words = topic().sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.TextBlock)
                .map(block -> ((guide.content.TextBlock) block).body())
                .mapToInt(body -> body.split("\\s+").length)
                .sum();
        assertTrue(words >= 2500 && words <= 3600,
                "topic prose out of range (2500–3600 words): " + words);
    }
}
