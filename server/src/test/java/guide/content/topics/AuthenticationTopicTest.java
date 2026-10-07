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
 * AuthenticationTopic registration test: the guide's biggest topic is
 * served in the auth-security group at order 1, carries its fourteen
 * sections in curriculum order (terminal companion last), every section
 * and block id is unique, all six demo islands are mounted inline in
 * their teaching sections, and the sourced-snippet discipline holds at
 * the topic's scale (17+ must-source blocks across java/xml/yaml/ts).
 */
class AuthenticationTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void authenticationTopicIsRegisteredWithItsFourteenSections() {
        Optional<Topic> found = registry.getTopicById("authentication");
        assertTrue(found.isPresent(), "authentication must be in the registry");
        Topic topic = found.get();
        assertEquals("Authentication", topic.title());
        assertEquals(TopicGroupId.AUTH_SECURITY, topic.group());
        assertEquals(1, topic.order());
        assertEquals(14, topic.sections().size());
        assertEquals("what-authentication-is", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("authentication").orElseThrow();
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
    void allSixDemoIslandsAreMountedInlineInTheirTeachingSections() {
        Topic topic = registry.getTopicById("authentication").orElseThrow();
        List<String> demoIds = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof DemoBlock)
                .map(block -> ((DemoBlock) block).demoId())
                .toList();
        assertEquals(List.of("auth:sessions", "auth:api-keys", "auth:jwt",
                "auth:oauth", "auth:hashing", "auth:refresh"), demoIds,
                "six demos, in curriculum order, inline in their sections");
    }

    @Test
    void theSourcedSnippetDisciplineHoldsAtThisTopicScale() {
        Topic topic = registry.getTopicById("authentication").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 17,
                "the topic quotes at least 17 sourced blocks, got " + sourced.size());
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the client demo is quoted (ts)");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())),
                "the yaml security configuration is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())),
                "the pom security dependencies are quoted");
    }
}
