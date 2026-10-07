package guide.domain.caching;

/**
 * The expensive read's ANSWER: word-count analytics for every post tagged
 * with one topic (posts carry lowercase tags; a "topic" here IS a tag).
 *
 * Immutable, and built ONLY by {@link SlowTopicStats} — so the invariants
 * (postCount never negative, min ≤ max, avg consistent with total) have one
 * keeper. {@link #empty(String)} is the legitimate zero-post answer, and it
 * is CACHEABLE like any other: caching an empty result is negative caching,
 * and it is often the biggest win in the house (the query for a thing that
 * does not exist is usually the most expensive one to keep repeating).
 */
public record TopicStatsReport(String topicId, int postCount, long totalWords,
        int minWords, int maxWords, double avgWords, String longestPostTitle) {

    public TopicStatsReport {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("topicId must be a non-blank string");
        }
        topicId = topicId.strip();
        if (postCount < 0 || minWords < 0 || maxWords < 0 || totalWords < 0) {
            throw new IllegalArgumentException("word-count analytics cannot be negative");
        }
        if (minWords > maxWords) {
            throw new IllegalArgumentException("minWords cannot exceed maxWords");
        }
        if (avgWords < 0 || Double.isNaN(avgWords) || Double.isInfinite(avgWords)) {
            throw new IllegalArgumentException("avgWords must be a finite non-negative number");
        }
        if (longestPostTitle == null) {
            longestPostTitle = "";
        }
    }

    /** The zero-post answer — cacheable, deliberately (negative caching). */
    public static TopicStatsReport empty(String topicId) {
        return new TopicStatsReport(topicId, 0, 0, 0, 0, 0.0, "");
    }
}
