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
 * ThirdPartyTopic registration test: the topic is served in the platform
 * group at order 14 (after best-practices 13 — Phase 11's curriculum
 * slot), carries its seven sections in curriculum order (terminal
 * companion last), every section and block id is unique, the FIRST
 * section mounts the thirdparty:hub demo INLINE (exactly one demo block
 * in the topic), and every must-source block carries a real repo source
 * path — java quoted from the topic's own ports/hub/keys, the three
 * adapters (including the REAL JavaMailSender-backed one), the demo
 * config and controller, plus the existing exemplars the topic
 * cross-quotes (topic 29's jobs code, the ScenarioStore's fake/live
 * switch, topic 36's redaction pattern); xml from the pom (the mail
 * starter); ts from the client demo. The curl tour rides as an exempt
 * bash block, never a fabricated source.
 */
class ThirdPartyTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void thirdPartyTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("third-party");
        assertTrue(found.isPresent(), "third-party must be in the registry");
        Topic topic = found.get();
        assertEquals("Third-Party Integrations", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(14, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("integrating-vs-building", topic.sections().getFirst().id());
        assertEquals("spring-mail", topic.sections().get(1).id());
        assertEquals("email-as-job", topic.sections().get(2).id());
        assertEquals("stripe", topic.sections().get(3).id());
        assertEquals("cloud-storage", topic.sections().get(4).id());
        assertEquals("keys-quotas-blast-radius", topic.sections().get(5).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("third-party").orElseThrow();
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
    void theHubDemoIsMountedInlineInTheFirstSection() {
        Topic topic = registry.getTopicById("third-party").orElseThrow();
        ContentSection first = topic.sections().getFirst();
        assertTrue(first.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "thirdparty:hub".equals(demo.demoId())),
                "the integrating-vs-building section must mount the thirdparty:hub demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void everyMustSourceBlockIsQuotedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("third-party").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 18, "the topic quotes at least 18 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/thirdparty/ThirdPartyHub.java")),
                "the hub's quota gate and blast-radius rows are quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/thirdparty/MailPort.java")),
                "the mail port is quoted in the Spring Mail section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/thirdparty/PaymentPort.java")),
                "the payments port is quoted in the Stripe section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/thirdparty/StoragePort.java")),
                "the storage port is quoted in the cloud-storage section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/thirdparty/ThirdPartyKeys.java")),
                "the key-verdict record is quoted in keys/quotas/blast radius");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/thirdparty/JavamailMailAdapter.java")),
                "the REAL JavaMailSender-backed adapter is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/thirdparty/FakeStripeAdapter.java")),
                "the provider's documented test vectors are quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/thirdparty/InMemoryObjectStore.java")),
                "the object store's provider discipline is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/ThirdPartyDemoConfig.java")),
                "the demo's composition-root wiring is quoted (sender timeouts + key verdicts)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("presentation/demo/ThirdPartyDemoController.java")),
                "the demo controller's trigger and dial endpoints are quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/jobs/EmailSender.java")),
                "topic 29's email port is cross-quoted in the recap");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/jobs/RecordingEmailSender.java")),
                "topic 29's recording fake is cross-quoted in the recap");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("core/scenario/ScenarioStore.java")),
                "the app's fake/live switch is cross-quoted in the posture intro");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/environments/InspectEnvironment.java")),
                "topic 36's redaction pattern is cross-quoted for key verdicts");
        assertTrue(sourced.stream().filter(block -> "xml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("server/pom.xml")),
                "every xml block comes from the pom (the mail starter)");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("thirdparty/hub_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "the curl tour rides as an exempt bash block, never a fabricated source");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("third-party").orElseThrow();
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
