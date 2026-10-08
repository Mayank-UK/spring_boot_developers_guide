package guide.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContentRegistryTest {

    private static TextBlock textBlock(String id) {
        return new TextBlock(id, "body");
    }

    private static Topic topic(String id, TopicGroupId group, int order, List<ContentSection> sections) {
        return new Topic(id, "Topic " + id, null, group, order, sections);
    }

    @Test
    void sortsByGroupOrderThenTopicOrder() {
        ContentRegistry registry = new ContentRegistry(List.of(
                topic("platform-late", TopicGroupId.PLATFORM, 2, List.of()),
                topic("fundamentals-2", TopicGroupId.FUNDAMENTALS, 2, List.of()),
                topic("fundamentals-1", TopicGroupId.FUNDAMENTALS, 1, List.of()),
                topic("apis-1", TopicGroupId.APIS, 1, List.of())));

        List<String> ids = registry.getTopics().stream().map(Topic::id).toList();
        assertEquals(List.of("fundamentals-1", "fundamentals-2", "apis-1", "platform-late"), ids);
    }

    @Test
    void throwsOnDuplicateTopicIds() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ContentRegistry(List.of(topic("a", TopicGroupId.FUNDAMENTALS, 1, List.of()),
                        topic("a", TopicGroupId.APIS, 1, List.of()))));
        assertTrue(error.getMessage().contains("duplicate topic id \"a\""), error.getMessage());
    }

    @Test
    void throwsOnDuplicateSectionIdsWithinOneTopic() {
        ContentSection section = new ContentSection("s", "S", null, List.of(textBlock("b")));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ContentRegistry(List.of(topic("a", TopicGroupId.FUNDAMENTALS, 1,
                        List.of(section, new ContentSection("s", "S again", null, List.of()))))));
        assertTrue(error.getMessage().contains("duplicate section id \"s\""), error.getMessage());
    }

    @Test
    void throwsOnDuplicateBlockIdsWithinOneTopicAcrossSections() {
        ContentSection sectionOne = new ContentSection("s1", "S1", null, List.of(textBlock("dup")));
        ContentSection sectionTwo = new ContentSection("s2", "S2", null, List.of(textBlock("dup")));
        Topic duplicated = topic("a", TopicGroupId.FUNDAMENTALS, 1, List.of(sectionOne, sectionTwo));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ContentRegistry(List.of(duplicated)));
        assertTrue(error.getMessage().contains("duplicate block id \"dup\""), error.getMessage());
    }

    @Test
    void throwsOnUnknownGroupAndBlankIds() {
        IllegalStateException groupError = assertThrows(IllegalStateException.class,
                () -> new ContentRegistry(List.of(topic("a", null, 1, List.of()))));
        assertTrue(groupError.getMessage().contains("unknown group"), groupError.getMessage());

        IllegalStateException blankError = assertThrows(IllegalStateException.class,
                () -> new ContentRegistry(List.of(topic(" ", TopicGroupId.FUNDAMENTALS, 1, List.of()))));
        assertTrue(blankError.getMessage().contains("must not be blank"), blankError.getMessage());
    }

    @Test
    void findsTopicsByExactIdOnly() {
        ContentRegistry registry = new ContentRegistry(List.of(
                topic("spring-core", TopicGroupId.FUNDAMENTALS, 5, List.of())));
        assertTrue(registry.getTopicById("spring-core").isPresent());
        assertFalse(registry.getTopicById("Spring-Core").isPresent());
        assertFalse(registry.getTopicById("a.b").isPresent());
        assertFalse(registry.getTopicById(null).isPresent());
    }

    @Test
    void javaLanguageTopicIsRegisteredWithItsNineSections() {
        ContentRegistry registry = new ContentRegistry(ContentTopics.all());

        Optional<Topic> topic = registry.getTopicById("java-language");
        assertTrue(topic.isPresent(), "java-language must be in the registry");
        assertEquals("Java Language Fundamentals", topic.get().title());
        assertEquals(TopicGroupId.FUNDAMENTALS, topic.get().group());
        assertEquals(9, topic.get().sections().size());
        assertEquals("terminal-companion", topic.get().sections().getLast().id());
    }

    @Test
    void summariesNeverCarryTheSectionsPayload() throws Exception {
        ContentSection section = new ContentSection("s1", "S1", "lead", List.of(textBlock("b1")));
        ContentRegistry registry = new ContentRegistry(List.of(
                topic("spring-core", TopicGroupId.FUNDAMENTALS, 5, List.of(section))));

        List<TopicSummary> summaries = registry.getTopicSummaries();
        assertEquals(1, summaries.size());
        assertEquals(1, summaries.getFirst().sectionCount());

        String json = JsonMapper.builder().build().writeValueAsString(summaries);
        JsonNode parsed = JsonMapper.builder().build().readTree(json);
        assertFalse(parsed.get(0).has("sections"), "summaries must not carry sections: " + json);
        assertFalse(parsed.get(0).has("blocks"), "summaries must not carry blocks: " + json);
    }
}
