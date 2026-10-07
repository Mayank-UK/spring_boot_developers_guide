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
 * PerformanceTopic registration test: the topic is served in the platform
 * group at order 9 (after environments 7 and deployment 8 — Phase 10's
 * curriculum slots), carries its eight sections in curriculum order
 * (terminal companion last), every section and block id is unique, the
 * compression section mounts the perf:bench demo INLINE, and every
 * must-source block carries a real repo source path — java quoted from the
 * benchmark engine, the use case's parameter policy and reading assembly,
 * the composition root's property reads, the jvm exhibit's pool discipline,
 * and the demo controller; yaml from application.yml (compression, virtual
 * threads, hikari); ts from the client demo. JFR/jcmd/GC tours ride as
 * exempt bash blocks, never fabricated sources.
 */
class PerformanceTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void performanceTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("performance");
        assertTrue(found.isPresent(), "performance must be in the registry");
        Topic topic = found.get();
        assertEquals("Performance", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(9, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("measure-first-jfr", topic.sections().getFirst().id());
        assertEquals("thread-hygiene", topic.sections().get(1).id());
        assertEquals("compression-benchmark", topic.sections().get(2).id());
        assertEquals("virtual-threads-at-scale", topic.sections().get(3).id());
        assertEquals("hikaricp-pool", topic.sections().get(4).id());
        assertEquals("gc-g1-zgc", topic.sections().get(5).id());
        assertEquals("performance-checklist", topic.sections().get(6).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("performance").orElseThrow();
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
    void theBenchDemoIsMountedInlineInTheCompressionSection() {
        Topic topic = registry.getTopicById("performance").orElseThrow();
        ContentSection compression = topic.sections().stream()
                .filter(section -> section.id().equals("compression-benchmark"))
                .findFirst().orElseThrow();
        assertTrue(compression.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "perf:bench".equals(demo.demoId())),
                "the compression section must mount the perf:bench demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void javaYamlAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("performance").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/performance/CompressionBenchmark.java")),
                "the benchmark's timing loop and engine are quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/performance/RunCompressionBenchmark.java")),
                "the use case's parameter policy and reading assembly are quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/PerfDemoConfig.java")),
                "the composition root's effective-config read is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/jvm/ThreadsExhibit.java")),
                "the jvm exhibit's pool discipline and virtual run are quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("PerfDemoController.java")),
                "the demo controller is quoted in the terminal companion");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("application.yml")),
                "every yaml block comes from application.yml (compression, threads, hikari)");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language())).count() >= 3,
                "compression, virtual-threads and hikari blocks are each quoted");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("perf_bench_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "JFR/jcmd/GC tours ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("performance").orElseThrow();
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
