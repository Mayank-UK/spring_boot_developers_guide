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
 * DependencyManagementTopic registration test: the topic is served in the
 * platform group at order 2, carries its seven sections in curriculum
 * order (terminal companion last), every section and block id is unique,
 * the deps:manifest demo is mounted INLINE inside the tree-and-enforcer
 * section where mediation and the drift guard are taught, and every
 * must-source block carries a real repo source path — the pom is quoted as
 * xml, both prod Dockerfiles as dockerfile, the probe/sync-test as
 * java, and the client demo as ts.
 */
class DependencyManagementTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void dependencyManagementTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("dependency-management");
        assertTrue(found.isPresent(), "dependency-management must be in the registry");
        Topic topic = found.get();
        assertEquals("Dependency Management", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(2, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("dependencies-are-product-surface", topic.sections().getFirst().id());
        assertEquals("the-tree-and-the-enforcer", topic.sections().get(2).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("dependency-management").orElseThrow();
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
    void treeSectionCarriesTheInlineManifestDemo() {
        Topic topic = registry.getTopicById("dependency-management").orElseThrow();
        ContentSection tree = topic.sections().stream()
                .filter(section -> section.id().equals("the-tree-and-the-enforcer"))
                .findFirst()
                .orElseThrow();
        assertTrue(tree.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "deps:manifest".equals(demo.demoId())),
                "the tree-and-enforcer section must mount the deps:manifest demo inline");
    }

    @Test
    void theRealPomDockerfileAndTestsAreQuotedAsSourcedBlocks() {
        Topic topic = registry.getTopicById("dependency-management").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())
                        && block.source().endsWith("server/pom.xml")),
                "the pom itself is quoted as xml");
        assertTrue(sourced.stream().anyMatch(block -> "dockerfile".equals(block.language())
                        && block.source().endsWith("Dockerfile.server.prod")),
                "the layered-jar build and COPY order are quoted from the prod server image");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("RuntimeVersionProbe.java")),
                "the two-tier probe is quoted where runtime truth is taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("FlaggedDecisionsPomSyncTest.java")),
                "the drift-guard test itself is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("manifest_demo.tsx")),
                "at least one snippet is quoted from the client demo (ts)");
    }
}
