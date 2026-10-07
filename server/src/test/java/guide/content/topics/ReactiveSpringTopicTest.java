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
 * ReactiveSpringTopic registration test: the topic is served in the
 * platform group at order 12 (after scaling 11 — Phase 10 pair C's
 * curriculum slot), carries its nine sections in curriculum order
 * (terminal companion last), every section and block id is unique, the
 * backpressure section mounts the reactive:compare demo INLINE, and every
 * must-source block carries a real repo source path — java quoted from
 * the topic's own engines (CompareExecutionModels,
 * DemonstrateBackpressure), the demo controller's reactive-return
 * endpoints, the WebClient adapter and the topic's config, plus the
 * realtime topic's SSE controller as the servlet-native contrast; xml
 * from the pom (reactor provenance + the webclient starter); yaml from
 * application.yml (virtual threads + the JDBC posture); ts from the
 * client demo. The curl/BlockHound tours ride as exempt bash blocks,
 * never fabricated sources.
 */
class ReactiveSpringTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void reactiveSpringTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("reactive-spring");
        assertTrue(found.isPresent(), "reactive-spring must be in the registry");
        Topic topic = found.get();
        assertEquals("Reactive Spring", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(12, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("why-reactive", topic.sections().getFirst().id());
        assertEquals("flux-mono", topic.sections().get(1).id());
        assertEquals("webflux-controllers", topic.sections().get(2).id());
        assertEquals("webclient", topic.sections().get(3).id());
        assertEquals("backpressure", topic.sections().get(4).id());
        assertEquals("debugging", topic.sections().get(5).id());
        assertEquals("r2dbc", topic.sections().get(6).id());
        assertEquals("mvc-vs-webflux", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("reactive-spring").orElseThrow();
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
    void theCompareDemoIsMountedInlineInTheBackpressureSection() {
        Topic topic = registry.getTopicById("reactive-spring").orElseThrow();
        ContentSection backpressure = topic.sections().stream()
                .filter(section -> section.id().equals("backpressure"))
                .findFirst().orElseThrow();
        assertTrue(backpressure.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "reactive:compare".equals(demo.demoId())),
                "the backpressure section must mount the reactive:compare demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void javaXmlYamlAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("reactive-spring").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 12, "the topic quotes at least 12 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reactive/CompareExecutionModels.java")),
                "the comparison engine's pipeline and blocking halves are quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reactive/DemonstrateBackpressure.java")),
                "the drop/buffer choreography is quoted in backpressure");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("presentation/demo/ReactiveDemoController.java")),
                "the reactive-return controller endpoints are quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/reactive/WebClientSelfFetch.java")),
                "the WebClient adapter's fetch chain is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/ReactiveDemoConfig.java")),
                "the demo's composition-root wiring is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("presentation/realtime/SseTickerController.java")),
                "the servlet-native SSE contrast is quoted from the realtime topic's controller");
        assertTrue(sourced.stream().filter(block -> "xml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("server/pom.xml")),
                "every xml block comes from the pom (reactor provenance + the webclient starter)");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("application.yml")),
                "every yaml block comes from the real configuration");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("compare_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "curl/BlockHound tours ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("reactive-spring").orElseThrow();
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
