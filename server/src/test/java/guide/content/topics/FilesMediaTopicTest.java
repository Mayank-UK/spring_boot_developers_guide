package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentTopics;
import guide.content.ContentSection;
import guide.content.DemoBlock;
import guide.content.SnippetMatcher;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * FilesMediaTopic registration test: the topic is served in the
 * files-media group at order 1, carries its eight sections in curriculum
 * order (terminal companion last), every section and block id is unique,
 * the files:workbench demo is mounted INLINE in the section that teaches
 * receiving uploads safely, and the snippet budget is honest — every
 * must-source block carries a repo source path, with the upload handler,
 * the guard stream, the use case, the sniffer, the disposition encoder,
 * the range parser, the generator, the thumbnail call, the sanitizer, and
 * the id gate all quoted from the real files the suite compiles.
 */
class FilesMediaTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void filesMediaTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("files-media");
        assertTrue(found.isPresent(), "files-media must be in the registry");
        Topic topic = found.get();
        assertEquals("Files and Media", topic.title());
        assertEquals(TopicGroupId.FILES_MEDIA, topic.group());
        assertEquals(1, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("uploads-are-streams-not-payloads", topic.sections().get(0).id());
        assertEquals("receiving-uploads-safely", topic.sections().get(1).id());
        assertEquals("downloads-and-content-disposition", topic.sections().get(2).id());
        assertEquals("range-requests", topic.sections().get(3).id());
        assertEquals("streaming-with-backpressure", topic.sections().get(4).id());
        assertEquals("image-processing", topic.sections().get(5).id());
        assertEquals("path-traversal-and-safe-paths", topic.sections().get(6).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("files-media").orElseThrow();
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
    void theWorkbenchDemoIsMountedInlineInTheReceivingUploadsSection() {
        Topic topic = registry.getTopicById("files-media").orElseThrow();
        ContentSection receiving = topic.sections().stream()
                .filter(section -> section.id().equals("receiving-uploads-safely"))
                .findFirst().orElseThrow();
        assertTrue(receiving.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "files:workbench".equals(demo.demoId())),
                "receiving-uploads-safely must mount the files:workbench demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("files-media").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 14, "the topic quotes at least 14 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 10,
                "the domain units, the use cases, the store gate and the controller are quoted as java");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())),
                "the Thumbnailator dependency is quoted from the pom");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the client's range fetch is quoted from the workbench demo (ts)");
    }
}
