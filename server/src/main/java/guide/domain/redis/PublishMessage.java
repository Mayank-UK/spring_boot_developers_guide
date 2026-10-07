package guide.domain.redis;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: publish one message on the playground's pub/sub channel. The
 * judgment this layer owns is the CHANNEL CONTRACT: the demo's listener
 * subscribes to the pattern guide:demo:chan:*, so a channel that does not
 * start with "chan:" would be published successfully into silence — zero
 * receivers, nothing in the ring. That is Redis being honest about
 * fire-and-forget (publishing to a channel nobody subscribes to is NOT an
 * error), but it would make the demo lie by omission; the use case turns
 * the silent path into the typed validation failure instead, and a blank
 * channel defaults to the console's home channel.
 */
public class PublishMessage {

    /** The console's home channel (bare; the adapter namespaces it to guide:demo:chan:playground). */
    public static final String DEFAULT_CHANNEL = "chan:playground";

    /** What the demo needs back: where it landed, what it said, who received it. */
    public record Published(String bareChannel, String namespacedChannel, String message, long receivers) {
    }

    private final RedisPlayground playground;

    public PublishMessage(RedisPlayground playground) {
        this.playground = Objects.requireNonNull(playground, "playground");
    }

    /** Publishes {@code message}; blank channels default, non-chan: channels are the typed validation failure. */
    public Result<Published> publish(String channel, String message) {
        if (message == null || message.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "publish needs a message",
                    Map.of("fields", Map.of("value", "required for publish"))));
        }
        String bare = channel != null && !channel.isBlank() ? channel.strip() : DEFAULT_CHANNEL;
        if (!bare.startsWith("chan:")) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "channel \"" + bare + "\" is outside the demo's subscription "
                            + "(the listener hears guide:demo:chan:* — publish to chan:…)",
                    Map.of("fields", Map.of("key", "must start with \"chan:\""))));
        }
        long receivers = playground.publish(bare, message);
        return Result.success(new Published(bare, playground.namespaced(bare), message, receivers));
    }
}
