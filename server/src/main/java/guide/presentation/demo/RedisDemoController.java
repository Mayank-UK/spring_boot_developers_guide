package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.UnavailableException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.redis.InspectTtl;
import guide.domain.redis.PublishMessage;
import guide.domain.redis.RedisOpRequest;
import guide.domain.redis.RedisPlayground;
import guide.domain.redis.RecentMessages;
import guide.domain.redis.RunRedisOp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 20 (redis) — the structures console:
 *
 * - POST /api/demo/redis/op   → run one op ({op, key, value?, field?,
 *   score?, ttlSeconds?, start?, stop?}) on whichever playground the
 *   scenario points at — set/get/del, hset/hgetall, lpush/lrange,
 *   sadd/smembers, zadd/zrange, ttl, expire, publish — and answer
 *   {ok, result, namespacedKey, mode}: the full key the SERVER saw, and
 *   the mode that executed it (the namespace lesson and the flip lesson,
 *   on every response).
 * - GET  /api/demo/redis/recent → the pub/sub ring: the newest messages
 *   delivered on guide:demo:chan:* (live: through the real subscription;
 *   fake: short-circuited by the fake's publish).
 * - GET  /api/demo/redis/mode   → the area's current mode, honestly.
 *
 * Thin by the house rule: parse the body, hand the op to its use case
 * (ttl → InspectTtl, publish → PublishMessage, the rest → RunRedisOp),
 * map the Result onto the wire. injectFailure guards every endpoint first
 * — the same ScenarioStore exhibit as every other demo controller.
 */
@Tag(name = "Redis", description = "The redis topic's demo: the namespaced structures console "
        + "with TTL inspection and the pub/sub ring.")
@RestController
@RequestMapping("/api/demo/redis")
public class RedisDemoController {

    /** The op answer: what happened, under which full key, in which mode. */
    record RedisOpView(boolean ok, Object result, String namespacedKey, String mode) {
    }

    /** The pub/sub window: mode plus newest-first deliveries. */
    record RecentView(String area, String mode, List<RecentMessages.RecentMessage> messages) {
    }

    /** The area's honest current state. */
    record ModeView(String area, String dataMode) {
    }

    private final RunRedisOp runRedisOp;
    private final InspectTtl inspectTtl;
    private final PublishMessage publishMessage;
    private final RecentMessages recent;
    private final ScenarioStore scenarioStore;

    public RedisDemoController(RunRedisOp runRedisOp, InspectTtl inspectTtl,
            PublishMessage publishMessage, RecentMessages recent, ScenarioStore scenarioStore) {
        this.runRedisOp = runRedisOp;
        this.inspectTtl = inspectTtl;
        this.publishMessage = publishMessage;
        this.recent = recent;
        this.scenarioStore = scenarioStore;
    }

    /**
     * The console: one op per call. Unknown ops and missing operands are the
     * typed validation envelope; a live op while Redis is down is the typed
     * unavailable envelope — both arrive from the use cases/adapters, none
     * invented here.
     */
    @Operation(summary = "Run one redis structure op on the playground", operationId = "runRedisOp",
            description = "Executes via the current-mode store (fake in-memory or live server) "
                    + "and answers {ok, result, namespacedKey, mode} — the full key the server "
                    + "saw, which is the namespaced-adapter lesson on every response.")
    @PostMapping(value = "/op", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RedisOpView> op(@RequestBody RedisOpRequest request) {
        requireAvailable();
        Result<? extends Record> outcome = switch (request.op() == null ? "" : request.op()) {
            case RedisOpRequest.TTL_OP -> inspectTtl.inspect(request.key());
            case RedisOpRequest.PUBLISH_OP -> publishMessage.publish(request.key(), request.value());
            default -> runRedisOp.execute(request);
        };
        return switch (outcome) {
            case Success<? extends Record> success -> ResponseEntity.ok(view(success.value()));
            case Failed<? extends Record> failed -> throw AppException.of(failed.failure());
        };
    }

    /** The pub/sub ring — newest first, bounded (a window, never a queue). */
    @Operation(summary = "The recent pub/sub deliveries", operationId = "recentRedisMessages",
            description = "The newest messages delivered on guide:demo:chan:* — live mode through "
                    + "the real subscription container, fake mode through the fake's publish.")
    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RecentView> recent() {
        requireAvailable();
        return ResponseEntity.ok(new RecentView(RedisPlayground.AREA, mode(), recent.snapshot()));
    }

    /** The area's mode — what "flip the scenario" actually moved. */
    @Operation(summary = "The playground's current mode", operationId = "redisPlaygroundMode",
            description = "The 'redis' area's current data mode: fake (in-memory playground) or "
                    + "live (real server), as the scenario toggle leaves it.")
    @GetMapping(value = "/mode", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ModeView> modeEndpoint() {
        return ResponseEntity.ok(new ModeView(RedisPlayground.AREA, mode()));
    }

    // ---- edge helpers ----

    /** The area's mode as the wire spells it — what every op response carries. */
    private String mode() {
        return scenarioStore.dataModeFor(RedisPlayground.AREA) == ScenarioDataMode.LIVE
                ? "live" : "fake";
    }

    /** One use-case answer mapped onto the view; the namespaced key is whichever the op touched. */
    private RedisOpView view(Record outcome) {
        if (outcome instanceof RunRedisOp.OpCompleted completed) {
            return new RedisOpView(true, completed.result(), completed.namespacedKey(), mode());
        }
        if (outcome instanceof InspectTtl.TtlInsight insight) {
            return new RedisOpView(true, Map.of(
                    "exists", insight.exists(),
                    "hasExpiry", insight.hasExpiry(),
                    "secondsRemaining", insight.secondsRemaining() == null ? "" : insight.secondsRemaining()),
                    insight.namespacedKey(), mode());
        }
        PublishMessage.Published published = (PublishMessage.Published) outcome;
        return new RedisOpView(true, Map.of(
                "channel", published.namespacedChannel(),
                "message", published.message(),
                "receivers", published.receivers()),
                published.namespacedChannel(), mode());
    }

    /** ScenarioStore exhibit: with injectFailure on, the demo dependency is "down" — 503. */
    private void requireAvailable() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "redis demos are unavailable by scenario (injectFailure=true)",
                    Map.of("area", RedisPlayground.AREA));
        }
    }
}
