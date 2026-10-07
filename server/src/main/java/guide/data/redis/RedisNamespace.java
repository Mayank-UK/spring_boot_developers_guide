package guide.data.redis;

/**
 * THE NAMESPACE, owned here and nowhere else. Every key and channel this
 * guide's redis demos touch on a shared server carries the {@code guide:demo:}
 * prefix, and this class is the single place that knows it: routes and use
 * cases pass bare keys, adapters call {@link #prefixed(String)}, responses
 * surface the full key via the port's namespaced(). Keeping the prefix in
 * ONE adapter-layer constant is what makes the demo keyspace greppable,
 * cleanable (DEL guide:demo:* by hand), and impossible to collide with any
 * real data that happens to share the server — and what keeps "guide:demo:"
 * out of every file above the data layer.
 */
public final class RedisNamespace {

    /** The prefix every demo key and channel carries. */
    public static final String PREFIX = "guide:demo:";

    /** The pub/sub pattern the demo's listener subscribes to (channels under the namespace). */
    public static final String CHANNEL_PATTERN = PREFIX + "chan:*";

    private RedisNamespace() {
    }

    /** The full form of a bare key or channel; null/blank stays null/blank (never silently prefixed). */
    public static String prefixed(String bare) {
        if (bare == null || bare.isBlank()) {
            return bare;
        }
        return PREFIX + bare.strip();
    }
}
