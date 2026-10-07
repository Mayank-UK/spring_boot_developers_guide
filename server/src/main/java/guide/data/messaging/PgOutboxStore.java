package guide.data.messaging;

import guide.domain.messaging.DomainEvent;
import guide.domain.messaging.OutboxRelay;
import guide.domain.messaging.OutboxStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The outbox's PostgreSQL adapter — every SQL the pattern needs over the
 * V20 table, nothing more. Two things here deserve a second read:
 *
 * THE CLAIM (the interesting half): one UPDATE ... RETURNING statement does
 * the entire handoff atomically. The subquery picks unprocessed rows in
 * arrival order FOR UPDATE SKIP LOCKED — locked rows (another relay's
 * in-flight batch, on any connection, in any process) are INVISIBLE, not a
 * wait: SKIP LOCKED is what lets N relay replicas poll the same table
 * concurrently without ever handing the same row to two of them and without
 * ever serializing on each other. The outer UPDATE bumps attempts — the
 * claim is itself one attempt — and RETURNING hands back the claimed rows
 * with their new counts. No explicit transaction: a single statement is
 * atomic on its own.
 *
 * THE STATE MACHINE IN TWO COLUMNS: processed_at NULL means queued; NOT
 * NULL with attempts within the relay's threshold means delivered; NOT
 * NULL with attempts PAST it means dead-lettered. stats() counts exactly
 * that with FILTER clauses — no status column, no enum, no trigger.
 */
public final class PgOutboxStore implements OutboxStore {

    private static final String INSERT_SQL = """
            INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, headers)
            VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))""";

    /**
     * The claim: SKIP LOCKED hides other relays' in-flight rows; the outer
     * UPDATE bumps attempts and RETURNING answers the batch — one statement,
     * atomic, no waiting, no double-claim.
     */
    private static final String CLAIM_SQL = """
            UPDATE outbox SET attempts = attempts + 1
            WHERE id IN (
                SELECT id FROM outbox
                WHERE processed_at IS NULL
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id, aggregate_type, aggregate_id, event_type,
                      payload::text AS payload, headers::text AS headers,
                      attempts, created_at, processed_at""";

    private static final String MARK_SQL =
            "UPDATE outbox SET processed_at = now() WHERE processed_at IS NULL AND id = ?";

    /**
     * The two-column state machine, counted. The boundary matches the
     * relay's dead-letter rule exactly (dead = claimed MORE than
     * MAX_ATTEMPTS times): a row that finally published on its third claim
     * (attempts == threshold) is DELIVERED, not dead — counting it dead
     * would misread a late success as a poison.
     */
    private static final String STATS_SQL = """
            SELECT count(*) FILTER (WHERE processed_at IS NULL) AS pending,
                   count(*) FILTER (WHERE processed_at IS NOT NULL AND attempts <= ?) AS delivered,
                   count(*) FILTER (WHERE processed_at IS NOT NULL AND attempts > ?) AS dead
            FROM outbox""";

    private static final String RECENT_SQL = """
            SELECT id, aggregate_type, aggregate_id, event_type,
                   payload::text AS payload, headers::text AS headers,
                   attempts, created_at, processed_at
            FROM outbox ORDER BY id DESC LIMIT ?""";

    /** Instance mapper on purpose: parsing needs the ObjectMapper field. */
    private final RowMapper<OutboxEntry> entryMapper = (rs, rowNum) -> {
        Map<String, Object> payload = readJsonStatic(rs.getString("payload"),
                new TypeReference<Map<String, Object>>() {
                });
        Map<String, String> headers = readJsonStatic(rs.getString("headers"),
                new TypeReference<Map<String, String>>() {
                });
        Instant occurredAt = rs.getObject("created_at", Timestamp.class).toInstant();
        // The identity from the headers envelope (put there by append) —
        // the SAME eventId the use case minted, which is the whole point:
        // outbox row, stream entry, and dedupe set all agree on it.
        String eventId = headers.getOrDefault("eventId", "outbox-" + rs.getLong("id"));
        DomainEvent event = new DomainEvent(
                eventId,
                rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getString("event_type"), payload, headers, occurredAt);
        Timestamp processed = rs.getObject("processed_at", Timestamp.class);
        return new OutboxEntry(rs.getLong("id"), event, rs.getInt("attempts"),
                occurredAt, processed == null ? null : processed.toInstant());
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PgOutboxStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void append(DomainEvent event) {
        // The event's IDENTITY rides the headers envelope: the table (per
        // the migration's deliberate shape) has no event_id column, so the
        // eventId that the consumer's dedupe set will see is stored as
        // envelope metadata — the same id the use case minted once, and the
        // one the relay's XADD will carry back out.
        Map<String, String> headers = new java.util.LinkedHashMap<>(event.headers());
        headers.putIfAbsent("eventId", event.eventId());
        jdbc.update(INSERT_SQL, event.aggregateType(), event.aggregateId(), event.eventType(),
                mapper.writeValueAsString(event.payload()),
                mapper.writeValueAsString(headers));
    }

    @Override
    public List<OutboxEntry> claimPending(int limit) {
        return jdbc.query(CLAIM_SQL, entryMapper, Math.clamp(limit, 1, 100));
    }

    @Override
    public void markProcessed(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(ids.size());
        for (Long id : ids) {
            batch.add(new Object[] {id});
        }
        jdbc.batchUpdate(MARK_SQL, batch);
    }

    @Override
    public OutboxStats stats() {
        return jdbc.queryForObject(STATS_SQL, (rs, rowNum) -> new OutboxStats(
                rs.getLong("pending"), rs.getLong("delivered"), rs.getLong("dead")),
                OutboxRelay.MAX_ATTEMPTS, OutboxRelay.MAX_ATTEMPTS);
    }

    @Override
    public List<OutboxEntry> recent(int limit) {
        return jdbc.query(RECENT_SQL, entryMapper, Math.clamp(limit, 1, 50));
    }

    private <T> T readJsonStatic(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return (T) Map.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (RuntimeException e) {
            return (T) Map.of();
        }
    }
}
