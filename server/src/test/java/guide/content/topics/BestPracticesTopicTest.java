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
 * BestPracticesTopic registration test: the capstone synthesis topic is
 * served in the platform group at order 13 (after reactive-spring 12), with
 * its twelve sections in curriculum order (setup → anti-checklist, terminal
 * companion last), unique section and block ids, the best:checklist demo
 * mounted INLINE in the anti-checklist section (exactly one demo), and every
 * must-source block quoting a real repo file — the topic's whole method is
 * pointing at existing exemplars: AppConfig, CreatePost, FailureKind,
 * AuthenticateCredentials, PgPostRepository, PostRepository, BreakerPort,
 * RequestLoggingFilter, CompressionBenchmark, Result, the checklist engine
 * and the client demo; yaml from application.yml + deploy/k8s; xml
 * from the pom. The curl tour rides as an exempt bash block, never a
 * fabricated source.
 */
class BestPracticesTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void bestPracticesTopicIsRegisteredWithItsTwelveSections() {
        Optional<Topic> found = registry.getTopicById("best-practices");
        assertTrue(found.isPresent(), "best-practices must be in the registry");
        Topic topic = found.get();
        assertEquals("Best Practices", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(13, topic.order());
        assertEquals(12, topic.sections().size());
        assertEquals("setup", topic.sections().getFirst().id());
        assertEquals("api-discipline", topic.sections().get(1).id());
        assertEquals("security", topic.sections().get(2).id());
        assertEquals("data", topic.sections().get(3).id());
        assertEquals("reliability", topic.sections().get(4).id());
        assertEquals("observability", topic.sections().get(5).id());
        assertEquals("performance", topic.sections().get(6).id());
        assertEquals("operations", topic.sections().get(7).id());
        assertEquals("git-review", topic.sections().get(8).id());
        assertEquals("human-practices", topic.sections().get(9).id());
        assertEquals("anti-checklist", topic.sections().get(10).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("best-practices").orElseThrow();
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
    void theChecklistDemoIsMountedInlineInTheAntiChecklistSection() {
        Topic topic = registry.getTopicById("best-practices").orElseThrow();
        ContentSection antiChecklist = topic.sections().stream()
                .filter(section -> section.id().equals("anti-checklist"))
                .findFirst().orElseThrow();
        assertTrue(antiChecklist.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "best:checklist".equals(demo.demoId())),
                "the anti-checklist section must mount the best:checklist demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void everyMustSourceBlockQuotesTheCodebasesOwnExemplars() {
        Topic topic = registry.getTopicById("best-practices").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 15, "the topic quotes at least 15 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("core/AppConfig.java")),
                "the boot-validated typed config is quoted in setup");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/posts/CreatePost.java")),
                "the typed use case is quoted in api discipline");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("core/errors/FailureKind.java")),
                "the one error taxonomy is quoted in api discipline");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/auth/AuthenticateCredentials.java")),
                "the hash-and-verify boundary is quoted in security");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("data/posts/PgPostRepository.java")),
                "parameterized SQL is quoted in data");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/posts/PostRepository.java")),
                "the tri-state port is quoted in data");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/reliability/BreakerPort.java")),
                "the breaker port is quoted in reliability");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("presentation/filter/RequestLoggingFilter.java")),
                "the JSON logging filter is quoted in observability");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/performance/CompressionBenchmark.java")),
                "the measured benchmark is quoted in performance");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("core/result/Result.java")),
                "the sealed Result type is quoted in human practices");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/best/PracticeChecklist.java")),
                "the checklist engine is quoted in the anti-checklist section");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())
                        && block.source().endsWith("server/pom.xml")),
                "the pom parent (version management) is quoted in setup");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("application.yml")
                                || block.source().endsWith("deploy/k8s/deployment.yml")),
                "every yaml block comes from the real configuration or the k8s manifest");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("checklist_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "the curl tour rides as an exempt bash block, never a fabricated source");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("best-practices").orElseThrow();
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
