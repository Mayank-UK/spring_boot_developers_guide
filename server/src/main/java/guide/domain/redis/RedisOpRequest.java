package guide.domain.redis;

/**
 * One playground console request, exactly as the demo wire carries it: an
 * op name plus the handful of optional operands its ops use. The use cases
 * — not the controller — judge which fields an op actually requires, so the
 * validation rules live one layer below HTTP and are testable without it.
 *
 * Field-to-op mapping (everything optional unless a use case says otherwise):
 * key is the bare key (or, for publish, the bare channel); value is a string
 * value, a hash field's value, or a pushed member; field is the hash field;
 * score is the sorted-set score; ttlSeconds arms expiry (with set: SET EX);
 * start/stop are range bounds defaulting to Redis's 0/-1 (whole list/zset).
 */
public record RedisOpRequest(
        String op,
        String key,
        String value,
        String field,
        Double score,
        Long ttlSeconds,
        Long start,
        Long stop) {

    /** The ops the console executes through {@link RunRedisOp}. */
    public static final java.util.Set<String> STRUCTURE_OPS = java.util.Set.of(
            "set", "get", "del", "hset", "hgetall", "lpush", "lrange",
            "sadd", "smembers", "zadd", "zrange", "expire");

    /** ttl and publish route to their own use cases ({@link InspectTtl}, {@link PublishMessage}). */
    public static final String TTL_OP = "ttl";

    /** The demo's publishing op — fires at a channel, lands in the recent ring. */
    public static final String PUBLISH_OP = "publish";

    /** Every op name the console accepts, in teaching order. */
    public static final java.util.List<String> ALL_OPS = java.util.List.of(
            "set", "get", "del", "hset", "hgetall", "lpush", "lrange",
            "sadd", "smembers", "zadd", "zrange", "ttl", "expire", "publish");
}
