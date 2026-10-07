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
 * BackgroundJobsTopic registration test: the topic is served in the
 * realtime-jobs group at order 2, carries its eight sections in curriculum
 * order (terminal companion last), every section and block id is unique,
 * the jobs console demo is mounted INLINE inside the retries-and-backoff
 * section where the retry machinery is taught, and every must-source block
 * carries a real repo source path (java + ts quoted from the live demo).
 */
class BackgroundJobsTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void backgroundJobsTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("background-jobs");
        assertTrue(found.isPresent(), "background-jobs must be in the registry");
        Topic topic = found.get();
        assertEquals("Background Jobs", topic.title());
        assertEquals(TopicGroupId.REALTIME_JOBS, topic.group());
        assertEquals(2, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("why-work-moves-background", topic.sections().getFirst().id());
        assertEquals("retries-and-backoff", topic.sections().get(2).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("background-jobs").orElseThrow();
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
    void retriesSectionCarriesTheInlineConsoleDemo() {
        Topic topic = registry.getTopicById("background-jobs").orElseThrow();
        ContentSection retries = topic.sections().stream()
                .filter(section -> section.id().equals("retries-and-backoff"))
                .findFirst()
                .orElseThrow();
        assertTrue(retries.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "jobs:console".equals(demo.demoId())),
                "the retries-and-backoff section must mount the jobs:console demo inline");
    }

    @Test
    void javaAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("background-jobs").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 8, "the topic quotes at least 8 sourced java/ts blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every java/ts block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())));
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "at least one snippet is quoted from the client demo (ts)");
        assertTrue(sourced.stream().anyMatch(block -> block.language().equals("java")
                        && block.source().endsWith("JobsDemoConfig.java")),
                "the executor config itself is quoted where the knobs are taught");
    }
}
