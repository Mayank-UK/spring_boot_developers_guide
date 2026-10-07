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
 * AuthorizationTopic registration test: the topic is served in the
 * auth-security group at order 2 (right after authentication, which it
 * extends), carries its eight sections in curriculum order (terminal
 * companion last), every section and block id is unique, the single
 * demo island (authz:matrix) is mounted inline in its teaching section,
 * and the sourced-snippet discipline holds (16 must-source blocks
 * across java/ts).
 */
class AuthorizationTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void authorizationTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("authorization");
        assertTrue(found.isPresent(), "authorization must be in the registry");
        Topic topic = found.get();
        assertEquals("Authorization", topic.title());
        assertEquals(TopicGroupId.AUTH_SECURITY, topic.group());
        assertEquals(2, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("authentication-vs-authorization", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
        assertEquals(List.of("authentication-vs-authorization", "roles-and-authorities-rbac",
                "method-security-preauthorize-and-spel", "permissions-and-scopes",
                "ownership-the-object-level-check", "deny-by-default-fail-closed",
                "authorization-in-the-wild", "terminal-companion"),
                topic.sections().stream().map(ContentSection::id).toList(),
                "sections in curriculum order with kebab ids");
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("authorization").orElseThrow();
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
    void theMatrixDemoIsMountedInlineInItsTeachingSection() {
        Topic topic = registry.getTopicById("authorization").orElseThrow();
        List<String> demoIds = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof DemoBlock)
                .map(block -> ((DemoBlock) block).demoId())
                .toList();
        assertEquals(List.of("authz:matrix"), demoIds,
                "one demo, inline in the roles-and-authorities section");
        ContentSection rbac = topic.sections().get(1);
        assertTrue(rbac.blocks().stream().anyMatch(block -> block instanceof DemoBlock),
                "the demo lives in roles-and-authorities-rbac, not queued at the end");
    }

    @Test
    void theSourcedSnippetDisciplineHoldsAtThisTopicScale() {
        Topic topic = registry.getTopicById("authorization").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 16,
                "the topic quotes at least 16 sourced blocks, got " + sourced.size());
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the client demo is quoted (ts)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())),
                "the island's own java is quoted");
        assertTrue(sourced.stream().noneMatch(block -> "bash".equals(block.language())
                        && block.source() != null),
                "terminal blocks stay bash and exempt — never relabeled to dodge the check");
    }
}
