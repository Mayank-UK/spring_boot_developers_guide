package guide.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The typed-content guarantee: validates at construction — so the Spring
 * context never boots with broken content — on duplicate topic ids, duplicate
 * section or block ids within a topic, blank ids, or a null (unknown) group.
 * Serves topics sorted for display: group order first, then topic order.
 */
public final class ContentRegistry {

    private final List<Topic> topics;

    public ContentRegistry(List<Topic> unsorted) {
        validate(unsorted);
        List<Topic> sorted = new ArrayList<>(unsorted);
        sorted.sort(Comparator.comparingInt((Topic topic) -> topic.group().order())
                .thenComparingInt(Topic::order));
        this.topics = List.copyOf(sorted);
    }

    private static void validate(List<Topic> list) {
        Set<String> seenTopicIds = new HashSet<>();
        for (Topic topic : list) {
            Objects.requireNonNull(topic, "topic");
            requireNonBlank(topic.id(), "topic id");
            if (!seenTopicIds.add(topic.id())) {
                throw new IllegalStateException(
                        "content registry: duplicate topic id \"" + topic.id() + "\"");
            }
            if (topic.group() == null) {
                throw new IllegalStateException(
                        "content registry: topic \"" + topic.id() + "\" has unknown group \"null\"");
            }

            Set<String> seenSectionIds = new HashSet<>();
            Set<String> seenBlockIds = new HashSet<>();
            for (ContentSection section : topic.sections()) {
                Objects.requireNonNull(section, "section in topic \"" + topic.id() + "\"");
                requireNonBlank(section.id(), "section id in topic \"" + topic.id() + "\"");
                if (!seenSectionIds.add(section.id())) {
                    throw new IllegalStateException("content registry: duplicate section id \""
                            + section.id() + "\" in topic \"" + topic.id() + "\"");
                }
                for (ContentBlock block : section.blocks()) {
                    Objects.requireNonNull(block,
                            "block in section \"" + section.id() + "\" of topic \"" + topic.id() + "\"");
                    requireNonBlank(block.id(), "block id in topic \"" + topic.id() + "\"");
                    if (!seenBlockIds.add(block.id())) {
                        throw new IllegalStateException("content registry: duplicate block id \""
                                + block.id() + "\" in topic \"" + topic.id() + "\"");
                    }
                }
            }
        }
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("content registry: " + what + " must not be blank");
        }
    }

    /** All topics, ordered for display: group order first, then topic order. */
    public List<Topic> getTopics() {
        return topics;
    }

    /** Summaries for list views — never carries the sections payload. */
    public List<TopicSummary> getTopicSummaries() {
        return topics.stream().map(TopicSummary::from).toList();
    }

    /** Exact-match lookup; empty when no topic carries that id. */
    public Optional<Topic> getTopicById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return topics.stream().filter(topic -> topic.id().equals(id)).findFirst();
    }
}
