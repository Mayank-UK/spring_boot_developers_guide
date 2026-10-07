package guide.domain.messaging;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;
import java.util.Map;

/**
 * Use case: publish one event to the stream from the console — XADD, right
 * now, no outbox. Deliberately wired to the DIRECT publisher: a console's
 * events have no business mutation to be atomic with, so the naive
 * publisher is the honest choice HERE — and its weaknesses are what the
 * outbox half of the topic exists to fix, demonstrated side by side.
 */
public class PublishToStream {

    private final TeachingEventPublisher publisher;
    private final Clock clock;

    public PublishToStream(TeachingEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** What the console answers with: the stream id and the event as stored. */
    public record Published(String streamId, String stream, DomainEvent event) {
    }

    public Result<Published> execute(String eventType, Map<String, Object> payload) {
        if (eventType == null || eventType.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "an event needs a non-blank eventType", null));
        }
        String aggregateId = payload != null && payload.get("postId") instanceof String postId
                ? postId
                : "console";
        DomainEvent event = DomainEvent.of(eventType.strip(), DomainEvent.AGGREGATE_POST,
                aggregateId, payload == null ? Map.of() : payload, clock.instant());
        TeachingEventPublisher.PublishedEvent published = publisher.publish(event);
        return Result.success(
                new Published(published.streamId(), published.stream(), published.event()));
    }
}
