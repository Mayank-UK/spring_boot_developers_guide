package guide.data.messaging;

import guide.domain.messaging.DomainEvent;
import guide.domain.messaging.TeachingEventPublisher;
import java.util.Objects;

/**
 * THE NAIVE PUBLISHER — XADD now, here, in the caller's thread, with no
 * transactional partner. This is the implementation most systems start
 * with, and for events nobody would miss (metrics ticks, cache-warm hints)
 * it is a legitimate choice: cheapest possible path, zero moving parts.
 *
 * Its failure mode is the topic's sixth section: the moment a business
 * mutation and this publish must happen TOGETHER, they cannot — the XADD is
 * either before the commit (an event about a change that maybe never
 * happened) or after it (a change whose event maybe never happens). The
 * demo wires this beside {@link OutboxEventPublisher} on the same port and
 * crashes between the two systems on purpose; the use cases choose between
 * them at the composition root without knowing which is which.
 */
public final class RedisDirectEventPublisher implements TeachingEventPublisher {

    private final RedisStreamMessaging stream;

    public RedisDirectEventPublisher(RedisStreamMessaging stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public PublishedEvent publish(DomainEvent event) {
        return stream.append(event);
    }
}
