package guide.data.redis;

import guide.data.redis.RedisNamespace;
import guide.domain.redis.RecentMessages;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * The subscription side of the pub/sub demo: one Spring Data Redis
 * {@link MessageListener} that the {@code RedisMessageListenerContainer}
 * (wired in guide.config.RedisDemoConfig) registers against the pattern
 * {@code guide:demo:chan:*}. Every message the server pushes for a matching
 * channel lands in the bounded recent-messages ring — bytes to String by
 * hand because the listener API is deliberately serializer-free (it hands
 * you the wire's raw bytes; how to read them is the subscriber's decision).
 *
 * This object is dumb on purpose: no filtering, no parsing beyond decoding,
 * no policy. The container owns delivery; the ring owns retention; this
 * class is just the seam between them.
 */
public final class RecentMessageListener implements MessageListener {

    private final RecentMessages recent;

    public RecentMessageListener(RecentMessages recent) {
        this.recent = Objects.requireNonNull(recent, "recent");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        recent.append(channel, body);
        // The pattern this delivery matched — unused, but named here so the
        // signature's second parameter never looks accidental.
        Objects.requireNonNull(pattern);
    }
}
