package guide.domain.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.redis.InMemoryRedisPlayground;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The console's engine over the fake playground: every op dispatches to the
 * right port call and answers with the NAMESPACED key (the adapter's prefix
 * surfaced by the use case, never built here), and the validation judgments
 * — which operands each op requires — are typed failures, never NPEs in an
 * adapter and never 500s at the edge.
 */
class RunRedisOpTest {

    private final InMemoryRedisPlayground fake = new InMemoryRedisPlayground(new RecentMessages());
    private final RunRedisOp useCase = new RunRedisOp(fake);

    private static RedisOpRequest op(String op, String key, String value, String field,
            Double score, Long ttlSeconds) {
        return new RedisOpRequest(op, key, value, field, score, ttlSeconds, null, null);
    }

    @Test
    void setAnswersOkUnderTheNamespacedKeyAndCarriesTtlWhenGiven() {
        Result<RunRedisOp.OpCompleted> plain = useCase.execute(op("set", "greeting", "hi", null, null, null));
        assertEquals("OK", ((Success<RunRedisOp.OpCompleted>) plain).value().result());
        assertEquals("guide:demo:greeting", ((Success<RunRedisOp.OpCompleted>) plain).value().namespacedKey());

        useCase.execute(op("set", "cached", "blob", null, null, 60L));
        assertEquals(Optional.of(60L), fake.ttlOf("cached"),
                "set with ttlSeconds takes the SET EX path, atomically");
    }

    @Test
    void everyStructureOpDispatchesWithRedisSemantics() {
        useCase.execute(op("set", "k", "v", null, null, null));
        assertEquals("v", ((Success<RunRedisOp.OpCompleted>) useCase.execute(op("get", "k", null, null, null, null))).value().result());

        Result<RunRedisOp.OpCompleted> deleted = useCase.execute(op("del", "k", null, null, null, null));
        assertEquals(true, ((Success<RunRedisOp.OpCompleted>) deleted).value().result());

        useCase.execute(op("hset", "profile", "Ada", "name", null, null));
        assertEquals(Map.of("name", "Ada"),
                ((Success<RunRedisOp.OpCompleted>) useCase.execute(op("hgetall", "profile", null, null, null, null))).value().result());

        useCase.execute(op("lpush", "feed", "a", null, null, null));
        useCase.execute(op("lpush", "feed", "b", null, null, null));
        assertEquals(List.of("b", "a"),
                ((Success<RunRedisOp.OpCompleted>) useCase.execute(op("lrange", "feed", null, null, null, null))).value().result(),
                "lrange without bounds defaults to the whole list, newest first");

        useCase.execute(op("sadd", "tags", "spring", null, null, null));
        assertEquals(List.of("spring"),
                ((Success<RunRedisOp.OpCompleted>) useCase.execute(op("smembers", "tags", null, null, null, null))).value().result());

        useCase.execute(op("zadd", "league", "ada", null, 10.0, null));
        List<RedisPlayground.ScoredMember> board = (List<RedisPlayground.ScoredMember>)
                ((Success<RunRedisOp.OpCompleted>) useCase.execute(op("zrange", "league", null, null, null, null))).value().result();
        assertEquals("ada", board.getFirst().member());

        useCase.execute(op("set", "plain", "v", null, null, null));
        assertEquals(true, ((Success<RunRedisOp.OpCompleted>)
                useCase.execute(op("expire", "plain", null, null, null, 45L))).value().result());
    }

    @Test
    void absentReadsAreNullsNotErrors() {
        useCase.execute(op("set", "gone", "v", null, null, null));
        fake.delete("gone");
        Object result = ((Success<RunRedisOp.OpCompleted>) useCase.execute(op("get", "gone", null, null, null, null))).value().result();
        assertNull(result, "GET of an expired/deleted key answers null — a miss, not a failure");
    }

    @Test
    void validationFailuresAreTypedAndNameTheField() {
        assertValidation(useCase.execute(op("keystore", "k", null, null, null, null)), "unknown redis op");
        assertValidation(useCase.execute(op("set", null, "v", null, null, null)), "needs a key");
        assertValidation(useCase.execute(op("set", " ", "v", null, null, null)), "needs a key");
        assertValidation(useCase.execute(op("set", "k", null, null, null, null)), "value is required");
        assertValidation(useCase.execute(op("set", "k", "v", null, null, 0L)), "ttlSeconds must be positive");
        assertValidation(useCase.execute(op("hset", "k", "v", null, null, null)), "field is required");
        assertValidation(useCase.execute(op("lpush", "k", null, null, null, null)), "value is required");
        assertValidation(useCase.execute(op("sadd", "k", null, null, null, null)), "value is required");
        assertValidation(useCase.execute(op("zadd", "k", "m", null, null, null)), "score is required");
        assertValidation(useCase.execute(op("expire", "k", null, null, null, null)), "ttlSeconds must be present");
    }

    @Test
    void boundedRangesTravelThroughWhenProvided() {
        useCase.execute(op("lpush", "nums", "3", null, null, null));
        useCase.execute(op("lpush", "nums", "2", null, null, null));
        useCase.execute(op("lpush", "nums", "1", null, null, null));
        RedisOpRequest bounded = new RedisOpRequest("lrange", "nums", null, null, null, null, 0L, 1L);
        assertEquals(List.of("1", "2"),
                ((Success<RunRedisOp.OpCompleted>) useCase.execute(bounded)).value().result());
    }

    @Test
    void ttlInspectionAndPublishHaveTheirOwnUseCases() {
        useCase.execute(op("set", "withttl", "v", null, null, 60L));
        InspectTtl inspectTtl = new InspectTtl(fake);
        InspectTtl.TtlInsight insight = ((Success<InspectTtl.TtlInsight>) inspectTtl.inspect("withttl")).value();
        assertTrue(insight.exists());
        assertTrue(insight.hasExpiry());
        assertEquals(60L, insight.secondsRemaining());
        assertEquals("guide:demo:withttl", insight.namespacedKey());

        PublishMessage publish = new PublishMessage(fake);
        PublishMessage.Published published = ((Success<PublishMessage.Published>)
                publish.publish(null, "defaults to the home channel")).value();
        assertEquals("chan:playground", published.bareChannel());
        assertEquals("guide:demo:chan:playground", published.namespacedChannel());
    }

    private static void assertValidation(Result<RunRedisOp.OpCompleted> result, String expectedFragment) {
        assertTrue(result instanceof Failed<RunRedisOp.OpCompleted>, "expected a typed failure");
        Failed<RunRedisOp.OpCompleted> failed = (Failed<RunRedisOp.OpCompleted>) result;
        assertEquals(FailureKind.VALIDATION, failed.failure().kind());
        assertTrue(failed.failure().message().contains(expectedFragment),
                "message \"" + failed.failure().message() + "\" should contain \"" + expectedFragment + "\"");
    }
}
