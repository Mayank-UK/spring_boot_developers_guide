package guide.data.sqldemo;

import guide.domain.sqldemo.AuditTrailStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit demo's PostgreSQL adapter: a post UPDATE and an audit_log INSERT
 * that succeed or fail TOGETHER. The transaction boundary is @Transactional
 * — Spring wraps this bean in a proxy, opens the transaction before the
 * method body runs, and commits on a normal return / rolls back on any
 * throwable — so the two jdbc.update calls in transferAndAudit ride ONE
 * connection bound to the thread by the transaction manager (that is the
 * entire mechanism: JdbcTemplate asks DataSourceUtils for the CURRENT
 * transaction's connection, not a fresh one).
 *
 * Activation runs in the CONSTRUCTOR, deliberately outside any transaction:
 * this bean is a lazy proxy's target, so construction happens at the first
 * demo call — migrate + seed there, never inside the transfer (DDL and
 * seeding must not ride a business transaction).
 *
 * The audit row lands in topic 16's V2 audit_log: a bigserial arrival
 * counter, a loose (entity_type, entity_id) pair with NO foreign key — an
 * audit must be able to describe deleted rows — and a jsonb payload whose
 * shape this adapter owns ({@code action, titleBefore, titleAfter, actor}).
 * Timestamps mint at MICROS resolution before sending (the µs-nano lesson
 * from 6.0: timestamptz stores microseconds and PG rounds, so mint what
 * will survive the round-trip).
 *
 * NOT final, deliberately: @Transactional on transferAndAudit means Spring
 * proxies this class (CGLIB subclassing — the default proxyTargetClass), and
 * a final class cannot be subclassed. The AOP topic's proxy lesson, paid
 * here in practice: classes meant for proxying leave the final off.
 */
public class PgAuditTrail implements AuditTrailStore {

    private static final String SELECT_TITLE_SQL = "SELECT title FROM posts WHERE id = ?";

    private static final String UPDATE_POST_SQL =
            "UPDATE posts SET title = ?, updated_at = ?, version = version + 1 WHERE id = ?";

    private static final String INSERT_AUDIT_SQL = """
            INSERT INTO audit_log (entity_type, entity_id, actor, payload)
            VALUES ('post', ?, ?, CAST(? AS jsonb))""";

    private static final String TRAIL_COUNT_SQL = """
            SELECT count(*) FROM audit_log WHERE entity_type = 'post' AND entity_id = ?""";

    private static final String TRAIL_SQL = """
            SELECT id, actor, payload::text AS payload, changed_at
            FROM audit_log
            WHERE entity_type = 'post' AND entity_id = ?
            ORDER BY id""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PgAuditTrail(DbDemoActivation activation, JdbcTemplate jdbc, ObjectMapper mapper) {
        // Outside any transaction on purpose — see the class comment.
        activation.ensureReady();
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public java.util.Optional<String> titleOf(String postId) {
        return jdbc.query(SELECT_TITLE_SQL,
                (rs, rowNum) -> rs.getString("title"), UUID.fromString(postId))
                .stream().findFirst();
    }

    @Override
    public int trailCount(String postId) {
        Integer count = jdbc.queryForObject(TRAIL_COUNT_SQL, Integer.class, UUID.fromString(postId));
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public void transferAndAudit(String postId, String newTitle, String changedBy, boolean failAfter) {
        UUID id = UUID.fromString(postId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Write 1 of 2: the business mutation (title, honest updatedAt, version bump).
        jdbc.update(UPDATE_POST_SQL, newTitle, now.atOffset(ZoneOffset.UTC), id);
        if (failAfter) {
            // The deliberate crash: UNTYPED on purpose (a crash is not a domain
            // failure with an HTTP status) and thrown BETWEEN the two writes —
            // the proxy's rollback is the behavior under observation.
            throw new IllegalStateException(
                    "simulated crash between the post UPDATE and the audit INSERT — both writes roll back");
        }
        // Write 2 of 2: the trail row describing write 1 — same transaction,
        // so it exists if and only if the change committed.
        jdbc.update(INSERT_AUDIT_SQL, id, changedBy, payloadJson(newTitle, changedBy));
    }

    @Override
    public List<TrailEntry> trailFor(String postId) {
        return jdbc.query(TRAIL_SQL, (rs, rowNum) -> new TrailEntry(
                rs.getLong("id"),
                rs.getString("actor"),
                rs.getString("payload"),
                rs.getObject("changed_at", OffsetDateTime.class)),
                UUID.fromString(postId));
    }

    /** The audit payload: fixed key order (LinkedHashMap) so diffs read cleanly. */
    private String payloadJson(String titleAfter, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "title-change");
        payload.put("titleAfter", titleAfter);
        payload.put("actor", actor);
        return mapper.writeValueAsString(payload);
    }
}
