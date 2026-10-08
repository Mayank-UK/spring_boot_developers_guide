package guide.data.messaging;

import guide.core.errors.NotFoundException;
import guide.core.errors.ValidationException;
import guide.data.sqldemo.DbDemoActivation;
import guide.domain.messaging.DomainEvent;
import guide.domain.messaging.PostEventTransaction;
import guide.domain.messaging.TeachingEventPublisher;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox demo's TRANSACTIONAL ADAPTER — the @Transactional bracket the
 * whole proof hangs on. transactPublish does, on ONE connection inside ONE
 * commit: (1) the posts mutation (draft→published, version+1, honest
 * updatedAt), (2) optionally the event append, by calling the in-transaction
 * publisher INSIDE this method — JdbcTemplate borrows the transaction's
 * thread-bound connection, so the outbox row and the post update are two
 * writes in one unit of work. failBeforeCommit throws BETWEEN the two
 * writes: the proxy rolls the transaction back and BOTH writes vanish.
 *
 * The mutation goes over SQL directly (not the posts port) on purpose and
 * precedent — the posts PORT routes through the scenario router, which may
 * answer from the in-memory fake: a different STORE cannot share a
 * transaction. The audit demo (topic 17) made the same call for the same
 * reason; the lesson is that atomicity picks its friends store by store.
 *
 * Activation runs in the CONSTRUCTOR, outside any transaction (a lazy
 * proxy's target constructs at the first demo call): migrations + seed
 * there, never inside the business method. NOT final, deliberately:
 * @Transactional proxies this class via CGLIB subclassing.
 */
public class PgPostEventTransaction implements PostEventTransaction {

    private static final String SELECT_TITLE_SQL = "SELECT title FROM posts WHERE id = ?";

    private static final String PUBLISH_SQL = """
            UPDATE posts
            SET status = 'published', version = version + 1, updated_at = ?
            WHERE id = ?
            RETURNING version""";

    private final JdbcTemplate jdbc;

    public PgPostEventTransaction(DbDemoActivation activation, JdbcTemplate jdbc) {
        activation.ensureReady();
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public PostMutation transactPublish(String postId, DomainEvent event, boolean failBeforeCommit,
            @Nullable TeachingEventPublisher inTransactionPublisher) {
        UUID id = parse(postId);
        Optional<String> title = jdbc.query(SELECT_TITLE_SQL,
                (rs, rowNum) -> rs.getString("title"), id).stream().findFirst();
        if (title.isEmpty()) {
            throw new NotFoundException("no post carries id " + postId + " — nothing to publish",
                    java.util.Map.of("postId", postId));
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Write 1 of 2: the business mutation.
        Long newVersion = jdbc.queryForObject(PUBLISH_SQL, Long.class,
                now.atOffset(ZoneOffset.UTC), id);
        if (failBeforeCommit) {
            // The deliberate crash, UNTYPED on purpose (a crash is not a
            // domain failure): one transaction, both writes roll back.
            throw new IllegalStateException("simulated crash BETWEEN the post UPDATE and the "
                    + "event append — the transaction rolls back, so neither the publish nor "
                    + "the event survives");
        }
        // Write 2 of 2: the event append — present only on the outbox path,
        // and INSIDE this method, so it rides the same commit as write 1.
        if (inTransactionPublisher != null) {
            inTransactionPublisher.publish(event);
        }
        return new PostMutation(postId, title.orElseThrow(), event.eventId(),
                newVersion == null ? 0L : newVersion);
    }

    private static UUID parse(String postId) {
        try {
            return UUID.fromString(postId);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("postId must be a UUID",
                    java.util.Map.of("postId", String.valueOf(postId)), e);
        }
    }
}
