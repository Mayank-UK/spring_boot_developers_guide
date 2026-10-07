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
 * RealtimeTopic registration test: the topic is served in the realtime-jobs
 * group at order 1, carries its seven sections in curriculum order (terminal
 * companion last), every section and block id is unique, the ticker demo is
 * mounted INLINE inside the server-sent-events section and the chat demo
 * inside the websockets section, and the java/ts snippets are sourced from
 * the real realtime implementation files.
 */
class RealtimeTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void realtimeTopicIsRegisteredWithItsSevenSections() {
        Optional<Topic> found = registry.getTopicById("realtime");
        assertTrue(found.isPresent(), "realtime must be in the registry");
        Topic topic = found.get();
        assertEquals("Real-Time", topic.title());
        assertEquals(TopicGroupId.REALTIME_JOBS, topic.group());
        assertEquals(1, topic.order());
        assertEquals(7, topic.sections().size());
        assertEquals("push-models", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("realtime").orElseThrow();
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
    void eachDemoSitsInlineInItsTeachingSection() {
        Topic topic = registry.getTopicById("realtime").orElseThrow();
        ContentSection sse = topic.sections().stream()
                .filter(section -> section.id().equals("server-sent-events"))
                .findFirst().orElseThrow();
        assertTrue(sse.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "realtime:ticker".equals(demo.demoId())),
                "the server-sent-events section must mount the realtime:ticker demo inline");
        ContentSection ws = topic.sections().stream()
                .filter(section -> section.id().equals("websockets"))
                .findFirst().orElseThrow();
        assertTrue(ws.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "realtime:chat".equals(demo.demoId())),
                "the websockets section must mount the realtime:chat demo inline");
    }

    @Test
    void javaAndTsBlocksAreSourcedFromTheRealRealtimeFiles() {
        Topic topic = registry.getTopicById("realtime").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced java/ts blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every java/ts block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "at least one snippet is quoted from the client demo (ts)");
        assertTrue(sourced.stream().anyMatch(block -> block.source()
                        .endsWith("guide/domain/realtime/TickerFeed.java")),
                "the feed's own code is quoted");
    }

    @Test
    void proseDepthIsComprehensive() {
        Topic topic = registry.getTopicById("realtime").orElseThrow();
        int words = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.TextBlock)
                .mapToInt(block -> ((guide.content.TextBlock) block).body().split("\\s+").length)
                .sum();
        assertTrue(words >= 2700 && words <= 3600,
                "comprehensive depth: prose word count in range, was " + words);
    }
}
