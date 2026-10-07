package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.ContentBlock;
import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.CodeBlock;
import guide.content.DemoBlock;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Topic 02 (jvm) registration checks: present in the registry, correct group
 * and order, all eleven curriculum sections in order with unique ids, unique
 * block ids across the topic, both demos mounted inline in their teaching
 * sections, and every must-source code block sourced (the strict quote check
 * itself lives in SnippetSourcesTest).
 */
class JvmTopicTest {

    private static final ContentRegistry REGISTRY = new ContentRegistry(ContentTopics.all());

    @Test
    void jvmTopicIsRegisteredInFundamentals() {
        Optional<Topic> topic = REGISTRY.getTopicById("jvm");
        assertTrue(topic.isPresent(), "jvm must be in the registry");
        assertEquals("JVM Fundamentals", topic.get().title());
        assertEquals(TopicGroupId.FUNDAMENTALS, topic.get().group());
        assertEquals(2, topic.get().order());
    }

    @Test
    void carriesTheElevenCurriculumSectionsInOrder() {
        Topic topic = REGISTRY.getTopicById("jvm").orElseThrow();
        List<String> sectionIds = topic.sections().stream().map(ContentSection::id).toList();
        assertEquals(List.of("what-the-jvm-is", "threads-vs-event-loop", "memory-model",
                "streams-readers-channels", "nio-file", "toolchain", "process-env-signals",
                "concurrency-patterns", "garbage-collection", "pools-vs-virtual-threads",
                "terminal-companion"), sectionIds);
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUnique() {
        Topic topic = REGISTRY.getTopicById("jvm").orElseThrow();
        Set<String> sectionIds = new HashSet<>();
        Set<String> blockIds = new HashSet<>();
        for (ContentSection section : topic.sections()) {
            assertTrue(sectionIds.add(section.id()), "duplicate section id: " + section.id());
            for (ContentBlock block : section.blocks()) {
                assertTrue(blockIds.add(block.id()), "duplicate block id: " + block.id());
            }
        }
        assertEquals(11, sectionIds.size());
    }

    @Test
    void bothDemosAreMountedInlineInTheirTeachingSections() {
        Topic topic = REGISTRY.getTopicById("jvm").orElseThrow();

        ContentSection threads = topic.sections().get(1);
        boolean threadsDemo = threads.blocks().stream()
                .anyMatch(block -> block instanceof DemoBlock demo && demo.demoId().equals("jvm:threads"));
        assertTrue(threadsDemo, "jvm:threads demo must sit inside threads-vs-event-loop");

        ContentSection streaming = topic.sections().get(3);
        boolean streamingDemo = streaming.blocks().stream()
                .anyMatch(block -> block instanceof DemoBlock demo && demo.demoId().equals("jvm:streaming"));
        assertTrue(streamingDemo, "jvm:streaming demo must sit inside streams-readers-channels");
    }

    @Test
    void everyMustSourceCodeBlockCarriesASource() {
        Topic topic = REGISTRY.getTopicById("jvm").orElseThrow();
        for (ContentSection section : topic.sections()) {
            for (ContentBlock block : section.blocks()) {
                if (block instanceof CodeBlock code && !code.language().equals("bash")) {
                    assertTrue(code.source() != null && !code.source().isBlank(),
                            section.id() + "/" + block.id() + " must carry a source path");
                }
            }
        }
    }
}
