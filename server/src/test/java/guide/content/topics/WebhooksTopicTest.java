package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WebhooksTopic registration test: the topic is served in the apis group at
 * order 4, carries its seven sections in curriculum order (terminal
 * companion last), every section and block id is unique, and the inline
 * simulator demo is mounted inside the delivering section where the retry
 * machinery is taught.
 */
class WebhooksTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void webhooksTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("webhooks");
        assertTrue(found.isPresent(), "webhooks must be in the registry");
        Topic topic = found.get();
        assertEquals("Webhooks", topic.title());
        assertEquals(TopicGroupId.APIS, topic.group());
        assertEquals(4, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("webhooks-are-reversed-apis", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("webhooks").orElseThrow();
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
    void deliveringSectionCarriesTheInlineSimulatorDemo() {
        Topic topic = registry.getTopicById("webhooks").orElseThrow();
        ContentSection delivering = topic.sections().stream()
                .filter(section -> section.id().equals("delivering-retries-backoff-dead-lettering"))
                .findFirst()
                .orElseThrow();
        assertTrue(delivering.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "api:webhook-simulator".equals(demo.demoId())),
                "the delivering section must mount the api:webhook-simulator demo inline");
    }

    @Test
    void javaAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("webhooks").orElseThrow();
        List<guide.content.CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (guide.content.CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 8, "the topic quotes at least 8 sourced java/ts blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every java/ts block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "at least one snippet is quoted from the client demo (ts)");
    }
}
