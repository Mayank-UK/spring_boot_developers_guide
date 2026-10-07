package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * DebuggingTopic registration test: the topic is served in the platform
 * group at order 6 (after testing, order 5), carries its eight sections in
 * curriculum order (terminal companion last), every section and block id is
 * unique, the thread-dumps section mounts the debug:stall demo INLINE, and
 * every must-source block carries a real repo source path — java quoted
 * from the request-logging filter, the stall theater (both parked shapes),
 * the thread diagnostics, the start boundary and the demo controller, yaml
 * from application.yml's management block, ts from the client demo.
 */
class DebuggingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void debuggingTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("debugging");
        assertTrue(found.isPresent(), "debugging must be in the registry");
        Topic topic = found.get();
        assertEquals("Debugging", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(6, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("the-debugging-mindset", topic.sections().getFirst().id());
        assertEquals("println-limits", topic.sections().get(1).id());
        assertEquals("breakpoints-intellij-and-jdb", topic.sections().get(2).id());
        assertEquals("thread-dumps", topic.sections().get(3).id());
        assertEquals("heap-dumps", topic.sections().get(4).id());
        assertEquals("sampling-profilers", topic.sections().get(5).id());
        assertEquals("debugging-in-production", topic.sections().get(6).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("debugging").orElseThrow();
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
    void theStallDemoIsMountedInlineInTheThreadDumpsSection() {
        Topic topic = registry.getTopicById("debugging").orElseThrow();
        ContentSection threadDumps = topic.sections().stream()
                .filter(section -> section.id().equals("thread-dumps"))
                .findFirst().orElseThrow();
        assertTrue(threadDumps.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "debug:stall".equals(demo.demoId())),
                "the thread-dumps section must mount the debug:stall demo inline");
        assertTrue(threadDumps.blocks().stream()
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void javaYamlAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("debugging").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 7, "the topic quotes at least 7 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("InProcessStallTheater.java")),
                "the stall engine is quoted where taught (both its parked shapes)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ThreadDiagnostics.java")),
                "the in-process dump machinery is quoted in the thread-dumps story");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("StartStall.java")),
                "the demo's typed boundary is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("RequestLoggingFilter.java")),
                "the completion line is quoted against println in its section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("DebugDemoController.java")),
                "the demo controller is quoted in the terminal companion");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())
                        && block.source().endsWith("application.yml")),
                "the actuator exposure decision is quoted in the production section");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("debug_stall_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "tool invocations ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("debugging").orElseThrow();
        int words = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.TextBlock)
                .map(block -> (guide.content.TextBlock) block)
                .mapToInt(textBlock -> textBlock.body().split("\\s+").length)
                .sum();
        assertTrue(words >= 3000,
                "content depth mandate: the topic's text must be at least ~3,000 words (got " + words + ")");
    }
}
