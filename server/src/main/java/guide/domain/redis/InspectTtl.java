package guide.domain.redis;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: inspect one key's TTL story. Redis answers TTL with an integer
 * that overloads its sign (-2 no such key, -1 no expiry, N seconds left);
 * a typed layer owes its callers better than sentinel arithmetic. This use
 * case composes EXISTS + TTL — exactly how redis clients themselves turn
 * the wire's three-way answer into a fact — and returns the typed insight:
 * the key either does not exist, exists WITHOUT an expiry (a dangerously
 * immortal cache entry), or exists and dies in N seconds (rounded up, the
 * direction that never overstates remaining life).
 */
public class InspectTtl {

    /** The typed three-way TTL answer; secondsRemaining is null unless hasExpiry. */
    public record TtlInsight(
            String bareKey,
            String namespacedKey,
            boolean exists,
            boolean hasExpiry,
            Long secondsRemaining) {
    }

    private final RedisPlayground playground;

    public InspectTtl(RedisPlayground playground) {
        this.playground = Objects.requireNonNull(playground, "playground");
    }

    /** Inspect one key's expiry; a blank key is the typed validation failure. */
    public Result<TtlInsight> inspect(String key) {
        if (key == null || key.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "ttl inspection needs a key",
                    Map.of("fields", Map.of("key", "required for ttl"))));
        }
        String bare = key.strip();
        boolean exists = playground.exists(bare);
        var remaining = exists ? playground.ttlOf(bare) : java.util.Optional.<Long>empty();
        TtlInsight insight = new TtlInsight(
                bare,
                playground.namespaced(bare),
                exists,
                remaining.isPresent(),
                remaining.orElse(null));
        return Result.success(insight);
    }
}
