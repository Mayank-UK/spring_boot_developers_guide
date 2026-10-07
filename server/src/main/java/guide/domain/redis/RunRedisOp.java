package guide.domain.redis;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: run one structure op on the playground — the console's engine.
 * This layer owns exactly two judgments. VALIDATION: each op states which
 * operands it requires (set needs a value, hset needs field+value, zadd
 * needs score+value, expire needs ttlSeconds > 0), and a missing or
 * out-of-range operand is the typed validation failure — never an NPE in
 * the adapter, never a 500. And INTERPRETATION: Redis's wire-level
 * conventions (range bounds defaulting to 0/-1, "set with ttlSeconds means
 * SET EX") become explicit decisions here, where they can be read.
 *
 * The op itself is one port call; the answer is wrapped with the
 * NAMESPACED key so the response can show what the server actually stored
 * under — the adapter owns the prefix, this layer only surfaces it.
 */
public class RunRedisOp {

    /** A completed op: the answer, plus the full key the server saw. */
    public record OpCompleted(String namespacedKey, Object result) {
    }

    private final RedisPlayground playground;

    public RunRedisOp(RedisPlayground playground) {
        this.playground = Objects.requireNonNull(playground, "playground");
    }

    /** Executes one structural op; ttl/publish route to their own use cases upstream. */
    public Result<OpCompleted> execute(RedisOpRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.op() == null || !RedisOpRequest.STRUCTURE_OPS.contains(request.op())) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unknown redis op \"" + request.op() + "\"",
                    Map.of("fields", Map.of("op", "must be one of " + RedisOpRequest.ALL_OPS))));
        }
        String key = request.key();
        if (key == null || key.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "redis op \"" + request.op() + "\" needs a key",
                    Map.of("fields", Map.of("key", "required for " + request.op()))));
        }
        return dispatch(request.op().strip(), key.strip(), request);
    }

    private Result<OpCompleted> dispatch(String op, String key, RedisOpRequest request) {
        return switch (op) {
            case "set" -> set(key, request);
            case "get" -> completed(key, playground.get(key).orElse(null));
            case "del" -> completed(key, playground.delete(key));
            case "hset" -> hset(key, request);
            case "hgetall" -> completed(key, playground.hashGetAll(key));
            case "lpush" -> lpush(key, request);
            case "lrange" -> completed(key,
                    playground.listRange(key, start(request), stop(request)));
            case "sadd" -> sadd(key, request);
            case "smembers" -> completed(key, List.copyOf(playground.setMembers(key)));
            case "zadd" -> zadd(key, request);
            case "zrange" -> completed(key, playground.zRange(key, start(request), stop(request)));
            case "expire" -> expire(key, request);
            default -> throw new IllegalStateException("unreachable: op already validated: " + op);
        };
    }

    // ---- the ops with operands to judge ----

    /** SET — plain, or SET EX when ttlSeconds rides along (the cache-friendly default, demonstrated). */
    private Result<OpCompleted> set(String key, RedisOpRequest request) {
        String problem = required(request.value(), "value", "set");
        if (problem != null) {
            return invalid("set", "value", problem);
        }
        Long ttl = request.ttlSeconds();
        if (ttl != null && ttl <= 0) {
            return invalid("set", "ttlSeconds", "ttlSeconds must be positive when present");
        }
        if (ttl == null) {
            playground.set(key, request.value());
        } else {
            playground.setWithTtl(key, request.value(), Duration.ofSeconds(ttl));
        }
        return completed(key, "OK");
    }

    private Result<OpCompleted> hset(String key, RedisOpRequest request) {
        if (request.value() == null || request.value().isBlank()) {
            return invalid("hset", "value", required(request.value(), "value", "hset"));
        }
        if (request.field() == null || request.field().isBlank()) {
            return invalid("hset", "field", required(request.field(), "field", "hset"));
        }
        playground.hashSet(key, request.field(), request.value());
        return completed(key, "OK");
    }

    private Result<OpCompleted> lpush(String key, RedisOpRequest request) {
        String problem = required(request.value(), "value", "lpush");
        if (problem != null) {
            return invalid("lpush", "value", problem);
        }
        return completed(key, playground.listPush(key, request.value()));
    }

    private Result<OpCompleted> sadd(String key, RedisOpRequest request) {
        String problem = required(request.value(), "value", "sadd");
        if (problem != null) {
            return invalid("sadd", "value", problem);
        }
        return completed(key, playground.setAdd(key, request.value()));
    }

    private Result<OpCompleted> zadd(String key, RedisOpRequest request) {
        String problem = required(request.value(), "value", "zadd");
        if (problem == null && request.score() == null) {
            return invalid("zadd", "score", "score is required for zadd");
        }
        if (problem != null) {
            return invalid("zadd", "value", problem);
        }
        return completed(key, playground.zAdd(key, request.score(), request.value()));
    }

    /** EXPIRE — arms a TTL on an existing key; a missing key answers false, which is a fact, not an error. */
    private Result<OpCompleted> expire(String key, RedisOpRequest request) {
        Long ttl = request.ttlSeconds();
        if (ttl == null || ttl <= 0) {
            return invalid("expire", "ttlSeconds", "ttlSeconds must be present and positive");
        }
        return completed(key, playground.expire(key, Duration.ofSeconds(ttl)));
    }

    // ---- judgment helpers ----

    /** Range starts default to Redis's 0 (the head); negative counts from the end. */
    private static long start(RedisOpRequest request) {
        return request.start() != null ? request.start() : 0;
    }

    /** Range stops default to Redis's -1 (the tail) — the whole-collection slice. */
    private static long stop(RedisOpRequest request) {
        return request.stop() != null ? request.stop() : -1;
    }

    private static String required(String value, String field, String op) {
        return value == null || value.isBlank() ? field + " is required for " + op : null;
    }

    private static Result<OpCompleted> invalid(String op, String field, String problem) {
        return Result.failed(Failure.of(FailureKind.VALIDATION,
                "redis op \"" + op + "\" is invalid: " + problem,
                Map.of("fields", Map.of(field, problem))));
    }

    private Result<OpCompleted> completed(String bareKey, Object result) {
        return Result.success(new OpCompleted(playground.namespaced(bareKey), result));
    }
}
