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
 * AdvancedArchitectureTopic registration test: the FINAL curriculum topic
 * (44) is served in the platform group at order 15 (after third-party 14),
 * carries its nine sections in curriculum order (consistency map → DDD
 * vocabulary → monolith first → between services → sagas → CQRS → event
 * sourcing → tracing → terminal companion last), every section and block
 * id is unique, the sagas and event-sourcing sections mount their demos
 * INLINE (exactly two demo blocks, arch:saga and arch:eventstore, no
 * collisions with topic 32's arch:tracer), and every must-source block
 * carries a real repo source path — java quoted from the topic's own
 * engines (OrderSagas, EventStore), its wiring (PatternsDemoConfig,
 * PatternsDemoController) and the real exemplars it cross-quotes (the
 * messaging outbox trio, DependencyRuleTest + ArchExemptions, topic 33's
 * tracing three, the DDD artifacts, the JPA projections); ts from the
 * client demo. The curl tours ride as exempt bash blocks, never a
 * fabricated source.
 */
class AdvancedArchitectureTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void theFinalTopicIsRegisteredWithItsNineSectionsInCurriculumOrder() {
        Optional<Topic> found = registry.getTopicById("advanced-architecture");
        assertTrue(found.isPresent(), "advanced-architecture must be in the registry");
        Topic topic = found.get();
        assertEquals("Advanced Architecture", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(15, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("consistency-map", topic.sections().getFirst().id());
        assertEquals("ddd-vocabulary", topic.sections().get(1).id());
        assertEquals("monolith-first", topic.sections().get(2).id());
        assertEquals("between-services", topic.sections().get(3).id());
        assertEquals("sagas", topic.sections().get(4).id());
        assertEquals("cqrs", topic.sections().get(5).id());
        assertEquals("event-sourcing", topic.sections().get(6).id());
        assertEquals("tracing-across-boundaries", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("advanced-architecture").orElseThrow();
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
    void bothDemosAreMountedInlineBesideTheirSections() {
        Topic topic = registry.getTopicById("advanced-architecture").orElseThrow();
        ContentSection sagas = topic.sections().get(4);
        assertTrue(sagas.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "arch:saga".equals(demo.demoId())),
                "the sagas section must mount the arch:saga demo inline");
        ContentSection eventSourcing = topic.sections().get(6);
        assertTrue(eventSourcing.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "arch:eventstore".equals(demo.demoId())),
                "the event-sourcing section must mount the arch:eventstore demo inline");
        assertEquals(2, topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count(),
                "exactly two demos live in the topic, each beside its section");
    }

    @Test
    void everyMustSourceBlockIsQuotedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("advanced-architecture").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 19, "the topic quotes at least 19 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/patterns/OrderSagas.java")),
                "the saga engine is quoted where taught (step, unwind, reading)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/patterns/EventStore.java")),
                "the event store is quoted where taught (append, fold, replay)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/PatternsDemoConfig.java")),
                "the topic's composition-root wiring is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("presentation/demo/PatternsDemoController.java")),
                "the demo controller's saga trigger is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/messaging/DomainEvent.java")),
                "the DDD event record is cross-quoted in the vocabulary section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reliability/BreakerPort.java")),
                "the breaker port is cross-quoted as the DDD boundary");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("test/java/guide/arch/DependencyRuleTest.java")),
                "the dependency-rule test is cross-quoted in monolith-first");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/arch/ArchExemptions.java")),
                "the exemption ledger record is cross-quoted in monolith-first");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/messaging/OutboxStore.java")),
                "the outbox port is cross-quoted in between-services");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/messaging/OutboxEventPublisher.java")),
                "the outbox publisher is cross-quoted in between-services");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/messaging/PgOutboxStore.java")),
                "the outbox INSERT and SKIP LOCKED claim are cross-quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/jpa/PostSummary.java")),
                "the interface projection is cross-quoted in CQRS");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/jpa/PostTitleView.java")),
                "the class projection is cross-quoted in CQRS");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/obs/RestClientTraceProbe.java")),
                "the hand-built traceparent is cross-quoted in tracing");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("presentation/demo/ObsDemoController.java")),
                "the hop's echo is cross-quoted in tracing");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/obs/RunTracedHop.java")),
                "the traced business beat is cross-quoted in tracing");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("arch/eventstore_demo.tsx")),
                "the client event-store demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "the curl tours ride as exempt bash blocks, never a fabricated source");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("advanced-architecture").orElseThrow();
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
