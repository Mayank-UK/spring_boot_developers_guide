package guide.domain.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The redis playground PORT: what "try the structures on a real keyspace"
 * means in the domain's language. One method per Redis structure the topic
 * teaches — string get/set/delete, hash field write + whole-hash read, list
 * push + range, set add + members, sorted-set add + scored range — plus the
 * expiry trio (write-with-TTL, EXPIRE, TTL inspection), existence, and one
 * publish. No Lettuce, no RedisTemplate, no command names in the signatures;
 * those are adapter concerns (guide.data.redis), and the use cases above
 * this port run unchanged against the in-memory fake and the real server.
 *
 * THE NAMESPACE RULE, stated once here because it shapes every signature:
 * callers pass BARE keys ("league", "session:42") and read answers that
 * carry the FULL key ("guide:demo:league"). The prefix an adapter applies
 * to keep this guide's demo keyspace separable on a shared server is the
 * adapter's private business — nothing above this port ever builds or
 * parses it, and {@link #namespaced(String)} is the only sanctioned way to
 * learn what a bare key became.
 *
 * AREA is this port's ScenarioStore area ('redis'). Unlike 'api' (pinned
 * fake by PostsDemoConfig) this area deliberately carries NO pin: it follows
 * the global scenario toggle, so the public PATCH /api/demo/scenario flips
 * the playground between the in-memory fake and the live server at runtime —
 * the optional-service flip IS this demo's lesson.
 */
public interface RedisPlayground {

    /** The ScenarioStore area the redis demos route on (the 'redis' area). */
    String AREA = "redis";

    /**
     * One member of a sorted-set range: the member and the score it carries.
     * Redis returns both together (ZRANGE WITHSCORES) because the score is
     * usually the point of the query, not a decoration.
     */
    record ScoredMember(String member, double score) {
    }

    /** The full, prefixed form of a bare key or channel in this playground's namespace. */
    String namespaced(String bareKey);

    /** EXISTS — is the key present right now (after expiry is applied)? */
    boolean exists(String key);

    /** SET — stores a string value; returns the server's "OK". */
    String set(String key, String value);

    /** GET — the string value, or empty when absent/expired (Redis's nil). */
    Optional<String> get(String key);

    /** DEL — true when a key was actually removed, false when it was already gone. */
    boolean delete(String key);

    /** HSET — writes one field of the hash at {@code key} (an object, field by field). */
    void hashSet(String key, String field, String value);

    /** HGETALL — every field→value of the hash; empty when the key is absent. */
    Map<String, String> hashGetAll(String key);

    /** LPUSH — prepends {@code value}; returns the list's length after the push. */
    long listPush(String key, String value);

    /** LRANGE — the slice {@code [start, stop]} (inclusive; negatives count from the end, -1 = last). */
    List<String> listRange(String key, long start, long stop);

    /** SADD — adds one member; returns 1 when it was new, 0 when it was already present. */
    long setAdd(String key, String member);

    /** SMEMBERS — every member, sorted for a deterministic wire order in every adapter. */
    Set<String> setMembers(String key);

    /** ZADD — sets one member's score; returns 1 when the member was new, 0 when updated. */
    long zAdd(String key, double score, String member);

    /** ZRANGE — members in score order over {@code [start, stop]} (negatives count from the end), with scores. */
    List<ScoredMember> zRange(String key, long start, long stop);

    /** SET key value EX ttl — the cache-friendly write: the value arrives with its own death sentence. */
    void setWithTtl(String key, String value, Duration ttl);

    /** EXPIRE — arms a TTL on an existing key; false (and a no-op) when the key is absent. */
    boolean expire(String key, Duration ttl);

    /**
     * TTL — remaining seconds, rounded up. Empty collapses Redis's two
     * non-positive answers (-1 no expiry, -2 no key); {@link #exists}
     * disambiguates them — exactly how every redis client composes the fact.
     */
    Optional<Long> ttlOf(String key);

    /** PUBLISH — fires {@code message} at the channel; returns the receiver count (0 when nobody listens). */
    long publish(String channel, String message);
}
