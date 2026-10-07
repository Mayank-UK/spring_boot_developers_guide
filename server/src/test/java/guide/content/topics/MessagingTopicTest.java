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
 * MessagingTopic registration test: the topic is served in the
 * caching-messaging group at order 2, carries its nine sections in
 * curriculum order (terminal companion last), every section and block id is
 * unique, the two demos are mounted INLINE in the sections that teach them
 * (msg:stream at consumer groups, msg:outbox at the outbox), and the
 * snippet budget is honest — every must-source block carries a repo source
 * path, with the outbox migration quoted as sql and the console's request
 * assembly quoted from the client demo (ts).
 */
class MessagingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void messagingTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("messaging");
        assertTrue(found.isPresent(), "messaging must be in the registry");
        Topic topic = found.get();
        assertEquals("Messaging & Event Streaming", topic.title());
        assertEquals(TopicGroupId.CACHING_MESSAGING, topic.group());
        assertEquals(2, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("why-messaging", topic.sections().getFirst().id());
        assertEquals("two-families-logs-and-queues", topic.sections().get(1).id());
        assertEquals("consumer-groups-and-ordering", topic.sections().get(2).id());
        assertEquals("delivery-guarantees-and-the-myth", topic.sections().get(3).id());
        assertEquals("pending-entries-and-visibility-timeout", topic.sections().get(4).id());
        assertEquals("the-transactional-outbox", topic.sections().get(5).id());
        assertEquals("idempotent-consumers-exactly-once-achievable", topic.sections().get(6).id());
        assertEquals("kafka-and-rabbitmq-by-comparison", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("messaging").orElseThrow();
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
    void bothDemosAreMountedInlineInTheirTeachingSections() {
        Topic topic = registry.getTopicById("messaging").orElseThrow();
        ContentSection groups = topic.sections().stream()
                .filter(section -> section.id().equals("consumer-groups-and-ordering"))
                .findFirst().orElseThrow();
        assertTrue(groups.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "msg:stream".equals(demo.demoId())),
                "consumer-groups-and-ordering must mount the msg:stream demo inline");
        ContentSection outbox = topic.sections().stream()
                .filter(section -> section.id().equals("the-transactional-outbox"))
                .findFirst().orElseThrow();
        assertTrue(outbox.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "msg:outbox".equals(demo.demoId())),
                "the-transactional-outbox must mount the msg:outbox demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("messaging").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 8,
                "the event vocabulary, ports, adapters, relay, ticker and policy are quoted as java");
        assertTrue(sourced.stream().anyMatch(block -> "sql".equals(block.language())),
                "the outbox migration is quoted from V20__outbox.sql");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "the console's request assembly is quoted from the client demo (ts)");
    }
}
