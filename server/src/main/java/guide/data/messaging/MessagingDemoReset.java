package guide.data.messaging;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.messaging.EventCounters;
import guide.domain.reset.ResettableDemo;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ResettableDemo adapter for the messaging demos (topic 22) — the ONE area
 * whose state lives in real infrastructure (the area is pinned LIVE), so
 * this is the only reset that can actually FAIL while the servers are
 * down. It speaks the adapters' own dialects directly, beside the ports
 * rather than through them: the ports are the pattern's teaching surface
 * (append/claim/mark), and "wipe the demo's data" is not part of either
 * pattern's contract.
 *
 * What a reset clears, in order:
 * - the outbox TABLE (one DELETE — pending, delivered and dead rows alike);
 * - both STREAM KEYS (events and the dead-letter stream): DEL drops each
 *   stream AND its consumer-group state with it, so the pending-entries
 *   ledger goes too;
 * - the consumer group is then recreated empty (XGROUP CREATE MKSTREAM,
 *   the idempotent activation call) so the console works on the next hit
 *   without re-paying activation;
 * - the consumer's PROJECTION + dedupe memory (EventCounters.reset()).
 *
 * Concurrency, honestly: the outbox relay's gated ticker may be mid-batch
 * when the reset lands; a claim on the just-emptied table finds nothing,
 * and a markProcessed for already-deleted rows updates zero rows — the
 * sweep stays consistent without pausing anything.
 */
public final class MessagingDemoReset implements ResettableDemo {

    /** The shared sweep's stable key for this area. */
    static final String AREA = "messaging";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RedisStreamMessaging stream;
    private final EventCounters counters;

    public MessagingDemoReset(JdbcTemplate jdbc, StringRedisTemplate redis,
            RedisStreamMessaging stream, EventCounters counters) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.stream = Objects.requireNonNull(stream, "stream");
        this.counters = Objects.requireNonNull(counters, "counters");
    }

    @Override
    public String id() {
        return AREA;
    }

    @Override
    public Result<String> reset() {
        try {
            jdbc.update("DELETE FROM outbox");
            redis.delete(List.of(RedisStreamMessaging.STREAM_KEY, RedisStreamMessaging.DEAD_KEY));
            stream.ensureGroup();
            counters.reset();
            return Result.success("outbox table, stream + DLQ keys, pending ledger and "
                    + "consumer projection cleared");
        } catch (DataAccessException infraGone) {
            // Covers Redis trouble too (RedisConnectionFailureException IS a
            // DataAccessException) — the expected failure shape while PG or
            // Redis is down, reported as a row (reset=false), never an
            // aborted sweep.
            return Result.failed(Failure.of(FailureKind.UNAVAILABLE,
                    "messaging state lives in live PostgreSQL/Redis — unreachable right now ("
                            + infraGone.getClass().getSimpleName() + ")"));
        }
    }
}
