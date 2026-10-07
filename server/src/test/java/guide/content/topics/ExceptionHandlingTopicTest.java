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
 * ExceptionHandlingTopic registration test: the topic is served in the
 * platform group at order 1 (the group's first topic), carries its nine
 * sections in curriculum order (terminal companion last), every section and
 * block id is unique, the failure-injection section mounts the
 * errors:explorer demo INLINE where injection is taught, and every
 * must-source block carries a real repo source path — java quoted from the
 * taxonomy, the advice (including its framework and problem-detail
 * branches), the injector and the process net; yaml from the dev-profile
 * document the 404 story leans on; ts from the client explorer.
 */
class ExceptionHandlingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void exceptionHandlingTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("exception-handling");
        assertTrue(found.isPresent(), "exception-handling must be in the registry");
        Topic topic = found.get();
        assertEquals("Exception Handling", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(1, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("errors-as-values-vs-throws", topic.sections().getFirst().id());
        assertEquals("the-taxonomy-eight-kinds-one-envelope", topic.sections().get(1).id());
        assertEquals("controller-advice-the-global-handler", topic.sections().get(2).id());
        assertEquals("problemdetail-rfc-9457", topic.sections().get(3).id());
        assertEquals("the-404-problem", topic.sections().get(4).id());
        assertEquals("failure-injection", topic.sections().get(5).id());
        assertEquals("what-logs-when-things-fail", topic.sections().get(6).id());
        assertEquals("process-level-safety-nets", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("exception-handling").orElseThrow();
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
    void failureInjectionSectionCarriesTheInlineExplorerDemo() {
        Topic topic = registry.getTopicById("exception-handling").orElseThrow();
        ContentSection injection = topic.sections().stream()
                .filter(section -> section.id().equals("failure-injection"))
                .findFirst()
                .orElseThrow();
        assertTrue(injection.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "errors:explorer".equals(demo.demoId())),
                "the failure-injection section must mount the errors:explorer demo inline");
    }

    @Test
    void javaYamlAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("exception-handling").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 12, "the topic quotes at least 12 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> block.language().equals("java")
                        && block.source().endsWith("GlobalExceptionHandler.java")),
                "the advice itself is quoted, new branches included");
        assertTrue(sourced.stream().anyMatch(block -> block.language().equals("java")
                        && block.source().endsWith("InjectFailure.java")),
                "the injector use case is quoted where injection is taught");
        assertTrue(sourced.stream().anyMatch(block -> block.language().equals("java")
                        && block.source().endsWith("UncaughtFailureLogger.java")),
                "the process net is quoted where it is taught");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())
                        && block.source().endsWith("application.yml")),
                "the dev-profile yaml document is quoted in the 404 story");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("explorer_demo.tsx")),
                "the client explorer is quoted from the live demo (ts)");
    }
}
