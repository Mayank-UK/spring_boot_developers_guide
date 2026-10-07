package guide.domain.mongo;

/** One row of a "which topics get read" aggregation: the topic and how many visits it earned. */
public record TopicTally(String topicId, long visits) {

    public TopicTally {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("tally topicId must be a non-blank string");
        }
        if (visits < 1) {
            throw new IllegalArgumentException("a tally only exists for topics that were visited");
        }
    }
}
