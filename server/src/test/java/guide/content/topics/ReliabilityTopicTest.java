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
 * ReliabilityTopic registration test: the topic is served in the platform
 * group at order 10 (after deployment 8 and performance 9 — its Phase 10
 * curriculum slot), carries its nine sections in curriculum order
 * (terminal companion last), every section and block id is unique, the
 * circuit-breaker section mounts the reliability:breaker demo INLINE, and
 * every must-source block carries a real repo source path — java quoted
 * from the reliability domain (theater, retry policy, caller, status
 * reader), the demo config and controller, the webhooks backoff engine,
 * the idempotent consumer, the race scoreboard and the scenario store;
 * xml from pom.xml (resilience4j); yaml from application.yml (hikari as
 * bulkhead); ts from the client demo. The terminal curl tours ride as
 * exempt bash blocks, never fabricated sources.
 */
class ReliabilityTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void reliabilityTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("reliability");
        assertTrue(found.isPresent(), "reliability must be in the registry");
        Topic topic = found.get();
        assertEquals("Reliability", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(10, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("distributed-failure", topic.sections().getFirst().id());
        assertEquals("timeouts", topic.sections().get(1).id());
        assertEquals("retries", topic.sections().get(2).id());
        assertEquals("circuit-breaker", topic.sections().get(3).id());
        assertEquals("degradation", topic.sections().get(4).id());
        assertEquals("bulkheads", topic.sections().get(5).id());
        assertEquals("chaos", topic.sections().get(6).id());
        assertEquals("races", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("reliability").orElseThrow();
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
    void theBreakerDemoIsMountedInlineInTheCircuitBreakerSection() {
        Topic topic = registry.getTopicById("reliability").orElseThrow();
        ContentSection breaker = topic.sections().stream()
                .filter(section -> section.id().equals("circuit-breaker"))
                .findFirst().orElseThrow();
        assertTrue(breaker.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "reliability:breaker".equals(demo.demoId())),
                "the circuit-breaker section must mount the reliability:breaker demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void everyMustSourceBlockIsSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("reliability").orElseThrow();
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
                        && block.source().endsWith("domain/reliability/ResilientCaller.java")),
                "the retry loop and the breaker wrap are quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reliability/RetryPolicy.java")),
                "the timeout budget is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reliability/DownstreamTheater.java")),
                "the four-personality theater is quoted (timeout + chaos sections)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reliability/ReadBreakerStatus.java")),
                "the generated narration is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/ReliabilityDemoConfig.java")),
                "the composition root's port wiring is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/reliability/Resilience4jBreakerAdapter.java")),
                "the adapter that owns the Resilience4j instance is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ReliabilityDemoController.java")),
                "the demo controller is quoted in the terminal companion");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("webhooks/DeliveryEngine.java")),
                "the webhooks backoff curve and tick batch are quoted as existing infra");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("messaging/IdempotentConsumer.java")),
                "the idempotent consumer is quoted in the retries section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("sqldemo/RaceOutcome.java")),
                "the oversell scoreboard is quoted in the races section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("core/scenario/ScenarioStore.java")),
                "the scenario store is quoted (rail, degradation, visibility)");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())
                        && block.source().endsWith("pom.xml")),
                "the resilience4j dependency is quoted from the pom");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("application.yml")),
                "every yaml block comes from application.yml (hikari as bulkhead)");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("breaker_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "the terminal tours ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("reliability").orElseThrow();
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
