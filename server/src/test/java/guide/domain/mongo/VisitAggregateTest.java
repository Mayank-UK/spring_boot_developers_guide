package guide.domain.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The aggregation MATH pinned without any infrastructure — the oracle both
 * adapters must reproduce (the Mongo pipeline test runs against this
 * contract). Tie-breaking (count desc, then topic id asc), the histogram's
 * locale order, and half-up rounding are CONTRACTS: an API whose aggregate
 * answers changed with the storage mode would be lying to somebody.
 */
class VisitAggregateTest {

    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static VisitDocument visit(String topic, long durationMs, String locale, String id) {
        return new VisitDocument(id, topic, "/topics/" + topic,
                VisitDocument.sha256Hex("token-" + id), durationMs, T, locale);
    }

    @Test
    void topTopicsOrdersByCountThenTopicIdAscending() {
        List<VisitDocument> population = List.of(
                visit("jdbc-sql", 1, "en", "a"),
                visit("jdbc-sql", 2, "en", "b"),
                visit("jdbc-sql", 3, "en", "c"),
                visit("aop", 1, "en", "d"),
                visit("aop", 2, "en", "e"),
                visit("routing", 9, "en", "f"),
                visit("validation", 9, "en", "g"));

        List<TopicTally> top = VisitAggregate.topTopics(population, 10);

        assertEquals(List.of(
                new TopicTally("jdbc-sql", 3),
                new TopicTally("aop", 2),
                new TopicTally("routing", 1),
                new TopicTally("validation", 1)), top,
                "count desc, and the 1-vs-1 tie breaks on topic id ascending");
        assertEquals(2, VisitAggregate.topTopics(population, 2).size(), "limit truncates");
        assertEquals(List.of(), VisitAggregate.topTopics(population, 0), "limit 0 is empty");
    }

    @Test
    void histogramOrdersByLocaleAscending() {
        List<VisitDocument> population = List.of(
                visit("aop", 1, "ja", "a"),
                visit("aop", 1, "en", "b"),
                visit("aop", 1, "fr", "c"),
                visit("aop", 1, "de-DE", "d"),
                visit("aop", 1, "fr", "e"));

        assertEquals(List.of(
                new LocaleTally("de-DE", 1),
                new LocaleTally("en", 1),
                new LocaleTally("fr", 2),
                new LocaleTally("ja", 1)), VisitAggregate.byLocaleHistogram(population),
                "buckets locale-ascending, counts summed");
    }

    @Test
    void averageRoundsHalfUpInWholeMilliseconds() {
        assertEquals(0, VisitAggregate.avgDurationMs(List.of()), "empty population is 0");
        assertEquals(1500, VisitAggregate.avgDurationMs(List.of(
                visit("aop", 1000, "en", "a"), visit("aop", 2000, "en", "b"))));
        assertEquals(2, VisitAggregate.avgDurationMs(List.of(
                visit("aop", 1, "en", "a"), visit("aop", 2, "en", "b"))),
                "1.5 ms rounds half-up to 2");
        assertEquals(1, VisitAggregate.avgDurationMs(List.of(
                visit("aop", 1, "en", "a"), visit("aop", 1, "en", "b"))));
    }

    @Test
    void averageNarrowsToTheTopicSlice() {
        List<VisitDocument> population = List.of(
                visit("jdbc-sql", 100, "en", "a"),
                visit("jdbc-sql", 300, "en", "b"),
                visit("aop", 9_000, "en", "c"));

        assertEquals(200, VisitAggregate.avgDurationMs(population, "jdbc-sql"),
                "only the topic's durations count");
        assertEquals(3133, VisitAggregate.avgDurationMs(population, null),
                "null or blank topicId means the whole population");
    }

    @Test
    void talliesRefuseToLieAboutZeroCounts() {
        assertThrows(IllegalArgumentException.class, () -> new TopicTally("aop", 0));
        assertThrows(IllegalArgumentException.class, () -> new LocaleTally("en", 0));
    }
}
