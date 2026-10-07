package guide.domain.mongo;

import java.util.List;

/**
 * The read model GET /stats serves: every aggregate the console asks of the
 * visits, assembled in ONE answer so the demo renders a coherent snapshot
 * rather than four racing reads. topicVisits mirrors totalVisits unless a
 * topicId filter narrowed the call (filteredByTopic says which world the
 * numbers describe).
 */
public record VisitStats(long totalVisits, long topicVisits, boolean filteredByTopic,
        long avgDurationMs, List<TopicTally> topTopics, List<LocaleTally> localeHistogram) {

    public VisitStats {
        if (totalVisits < 0 || topicVisits < 0 || topicVisits > totalVisits) {
            throw new IllegalArgumentException("topicVisits must be between 0 and totalVisits");
        }
        topTopics = List.copyOf(topTopics);
        localeHistogram = List.copyOf(localeHistogram);
    }
}
