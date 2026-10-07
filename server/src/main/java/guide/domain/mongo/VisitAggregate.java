package guide.domain.mongo;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The aggregation logic as PURE FUNCTIONS over a list of documents — no
 * store, no Spring, no MongoDB, no clock. Every question the port asks has
 * exactly one implementation of the MATH here, so the in-memory fake
 * computes answers by calling these functions directly, unit tests pin the
 * arithmetic without any infrastructure, and the MongoDB adapter's $group
 * pipelines exist to answer THE SAME questions server-side — the parity
 * test (fake vs container-backed Mongo) is what keeps the two honest about
 * tie-breaking, rounding, and ordering.
 *
 * The tie-break and rounding rules are contracts, not conveniences: a
 * tally sorted differently per adapter would make the API's answers depend
 * on which storage was live — exactly the drift the port exists to prevent.
 */
public final class VisitAggregate {

    private VisitAggregate() {
    }

    /** Sort contract shared by both adapters: count descending, then topic id ascending. */
    public static final Comparator<TopicTally> TOPIC_ORDER =
            Comparator.comparingLong(TopicTally::visits).reversed()
                    .thenComparing(TopicTally::topicId);

    /** Mean duration in whole milliseconds, rounded half-up; 0 for an empty population. */
    public static long avgDurationMs(List<VisitDocument> visits) {
        if (visits.isEmpty()) {
            return 0;
        }
        long totalMicros = 0;
        for (VisitDocument visit : visits) {
            // Half-up rounding in fixed point (micros), because double averaging
            // drifts on large durations and Integer division truncates by surprise.
            totalMicros += visit.durationMs() * 1_000;
        }
        return (totalMicros + visits.size() * 1_000 / 2) / (visits.size() * 1_000);
    }

    /** Visits per topic, ordered by the shared contract, truncated to {@code limit}. */
    public static List<TopicTally> topTopics(List<VisitDocument> visits, int limit) {
        return countBy(visits, VisitDocument::topicId, TopicTally::new).stream()
                .sorted(TOPIC_ORDER)
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Visits per locale, locale ascending — the histogram's stable row order. */
    public static List<LocaleTally> byLocaleHistogram(List<VisitDocument> visits) {
        return countBy(visits, VisitDocument::locale, LocaleTally::new).stream()
                .sorted(Comparator.comparing(LocaleTally::locale))
                .toList();
    }

    /** The group-by helper: tally one keyed facet of the population. */
    private static <T> List<T> countBy(List<VisitDocument> visits,
            java.util.function.Function<VisitDocument, String> key,
            java.util.function.BiFunction<String, Long, T> tally) {
        Map<String, Long> counts = visits.stream()
                .collect(Collectors.groupingBy(key, Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> tally.apply(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Convenience overload of {@link #avgDurationMs} for one topic's slice. */
    public static long avgDurationMs(List<VisitDocument> visits, String topicId) {
        return avgDurationMs(topicId == null || topicId.isBlank()
                ? visits
                : visits.stream().filter(visit -> visit.topicId().equals(topicId)).toList());
    }

    /** Locale keys in the histogram's canonical order — handy for parity assertions. */
    public static List<String> localeKeys(List<LocaleTally> histogram) {
        return histogram.stream().map(LocaleTally::locale)
                .map(key -> key.toLowerCase(Locale.ROOT))
                .toList();
    }
}
