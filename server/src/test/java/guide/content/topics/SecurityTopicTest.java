package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.DemoBlock;
import guide.content.SnippetMatcher;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SecurityTopic registration test: the app-hardening topic is served in the
 * auth-security group at order 3, carries its TEN sections in curriculum
 * order (terminal companion last), every section and block id is unique,
 * both demo islands (the lab and the PII vault) are mounted inline in their
 * teaching sections, and the sourced-snippet discipline holds at the
 * topic's scale (14+ must-source blocks across java/xml/yaml/ts).
 */
class SecurityTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void securityTopicIsRegisteredWithItsTenSections() {
        Optional<Topic> found = registry.getTopicById("security");
        assertTrue(found.isPresent(), "security must be in the registry");
        Topic topic = found.get();
        assertEquals("Security", topic.title());
        assertEquals(TopicGroupId.AUTH_SECURITY, topic.group());
        assertEquals(3, topic.order());
        assertEquals(10, topic.sections().size());
        assertEquals("the-threat-model-habit", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("security").orElseThrow();
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
    void bothDemoIslandsAreMountedInlineInTheirTeachingSections() {
        Topic topic = registry.getTopicById("security").orElseThrow();
        List<String> demoIds = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof DemoBlock)
                .map(block -> ((DemoBlock) block).demoId())
                .toList();
        assertEquals(List.of("security:lab", "security:pii"), demoIds,
                "two demos, in curriculum order, inline in their sections");
        assertEquals("secure-headers-via-spring-security",
                topic.sections().stream()
                        .filter(section -> section.blocks().stream()
                                .anyMatch(block -> block instanceof DemoBlock demo
                                        && "security:lab".equals(demo.demoId())))
                        .findFirst().orElseThrow().id(),
                "the lab demo lives in the headers section");
        assertEquals("pii-retention-and-gdpr",
                topic.sections().stream()
                        .filter(section -> section.blocks().stream()
                                .anyMatch(block -> block instanceof DemoBlock demo
                                        && "security:pii".equals(demo.demoId())))
                        .findFirst().orElseThrow().id(),
                "the PII demo lives in the erasure section");
    }

    @Test
    void theSourcedSnippetDisciplineHoldsAtThisTopicScale() {
        Topic topic = registry.getTopicById("security").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 14,
                "the topic quotes at least 14 sourced blocks, got " + sourced.size());
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the client demos are quoted (ts)");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())),
                "the yaml secret configuration is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())),
                "the pom bucket4j dependency is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())),
                "the java security machinery is quoted");
    }
}
