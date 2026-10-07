package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
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
 * ArchitectureTopic registration test: the topic is served in the platform
 * group at order 3, carries its NINE sections in curriculum order (terminal
 * companion last), every section and block id is unique, the arch:tracer
 * demo is mounted INLINE inside the request-flow-tracer section (the topic's
 * centerpiece lands where the story is taught, never queued at page end),
 * and every must-source block carries a real repo source path — the
 * dependency-rule test itself, the exemption ledger, the controllers, the
 * composition roots, and both client-side snippets (ts).
 */
class ArchitectureTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void architectureTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("architecture");
        assertTrue(found.isPresent(), "architecture must be in the registry");
        Topic topic = found.get();
        assertEquals("Architecture", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(3, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("why-architecture-at-all", topic.sections().getFirst().id());
        assertEquals("the-layer-map", topic.sections().get(1).id());
        assertEquals("the-dependency-rule", topic.sections().get(2).id());
        assertEquals("thin-controllers", topic.sections().get(3).id());
        assertEquals("the-composition-root", topic.sections().get(4).id());
        assertEquals("errors-across-the-layers", topic.sections().get(5).id());
        assertEquals("the-clients-mirror", topic.sections().get(6).id());
        assertEquals("request-flow-tracer", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("architecture").orElseThrow();
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
    void theTracerDemoIsMountedInlineInTheTracerSection() {
        Topic topic = registry.getTopicById("architecture").orElseThrow();
        ContentSection tracer = topic.sections().stream()
                .filter(section -> section.id().equals("request-flow-tracer"))
                .findFirst()
                .orElseThrow();
        assertTrue(tracer.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "arch:tracer".equals(demo.demoId())),
                "the request-flow-tracer section must mount the arch:tracer demo inline");
    }

    @Test
    void theRealArtifactsAreQuotedAsSourcedBlocks() {
        Topic topic = registry.getTopicById("architecture").orElseThrow();
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
                        && block.source().endsWith("DependencyRuleTest.java")),
                "the enforcement test itself is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ArchExemptions.java")),
                "the exemption ledger is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ContentController.java")),
                "the thin-controller exemplar is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("PostsDemoConfig.java")),
                "the composition-root router is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ArchDemoConfig.java")),
                "the topic's own composition root is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("Result.java")),
                "the Result seam contract is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ProbeInternalApi.java")),
                "the values-not-throws specimen is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("result.ts")),
                "the client's mirrored Result is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("tracer_demo.tsx")),
                "the client tracer widget is quoted");
    }
}
