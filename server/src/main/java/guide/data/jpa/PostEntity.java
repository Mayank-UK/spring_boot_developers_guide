package guide.data.jpa;

import guide.domain.posts.PostStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLRestriction;

/**
 * The posts table (V1) as an entity — the SAME twelve rows PgPostRepository
 * reads with JdbcTemplate, mapped a second way on purpose: the two
 * implementations side by side are this topic's honest ORM comparison.
 *
 * Mapping decisions, each one a section in the topic:
 * - id is app-minted (SeedPosts' stable UUIDs, the probe's deterministic
 *   specimen ids) — @Id with NO @GeneratedValue, because identity in this
 *   domain is born in the application, never in a sequence.
 * - status goes through PostStatusConverter (wire words, not constant names
 *   — the CHECK constraint insists); tags through StringListConverter
 *   (List&lt;String&gt; over text[]).
 * - version maps the EXISTING optimistic-concurrency column: @Version int —
 *   the same discipline topic 17 enforced by hand ("WHERE version = ?"),
 *   now enforced by Hibernate's dirty-check UPDATE.
 * - createdAt/updatedAt are stamped by the @PrePersist/@PreUpdate listeners
 *   below — zero-config auditing that works in every context (a listener is
 *   part of the entity, unlike @EnableJpaAuditing which needs a config
 *   class); they stamp only when the field is empty, so re-saving a seed row
 *   can never rewrite history.
 * - entries is the one association: posts -> audit_log (V2), joined on
 *   entity_id with NO foreign key to either side of it — audit_log is a
 *   polymorphic ledger (entity_type + entity_id), so @SQLRestriction keeps
 *   the join honest ("only the ledger rows that are about posts"). It is
 *   mapped TWICE, and that is the probe's instrument rig, not a modeling
 *   recommendation: `entries` is plain LAZY (the N+1 specimen), while
 *   `batchedEntries` carries @BatchSize(8) (the batching specimen). A real
 *   model picks ONE shape; the A/B rig needs both against the same rows.
 */
@Entity
@Table(name = "posts")
public class PostEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Convert(converter = PostStatusConverter.class)
    @Column(name = "status", nullable = false, length = 10)
    private PostStatus status;

    /** The optimistic-lock token: every UPDATE quotes it; a mismatch is a 409, not a lost update. */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Convert(converter = StringListConverter.class)
    @Column(name = "tags", nullable = false)
    private List<String> tags = List.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The audit ledger rows about this post — LAZY, so touching it per row IS the N+1. */
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id")
    @SQLRestriction("entity_type = 'post'")
    private List<AuditEntryEntity> entries = new ArrayList<>();

    /** Same association, same rows, one difference: @BatchSize(8) collapses the lazy storm. */
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id")
    @SQLRestriction("entity_type = 'post'")
    @BatchSize(size = 8)
    private List<AuditEntryEntity> batchedEntries = new ArrayList<>();

    /** JPA needs the no-arg constructor; everyone else should use the full one below. */
    protected PostEntity() {
    }

    public PostEntity(UUID id, String title, String body, PostStatus status, List<String> tags) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.status = status;
        // The entity defends exactly like the converter: a null tag list is an
        // empty one (the column is NOT NULL DEFAULT '{}').
        this.tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** Listener auditing, part one: birth stamps both clocks — only when unset. */
    @PrePersist
    void stampAtBirth() {
        if (createdAt == null) {
            Instant now = Instant.now();
            createdAt = now;
            updatedAt = now;
        }
    }

    /** Listener auditing, part two: every accepted UPDATE refreshes updatedAt (checked >= created by the table). */
    @PreUpdate
    void stampAtUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    /** The probe mutates titles to make dirty rows; no other writer exists. */
    public void retitle(String next) {
        this.title = next;
    }

    public String getBody() {
        return body;
    }

    public PostStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public List<String> getTags() {
        return tags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<AuditEntryEntity> getEntries() {
        return entries;
    }

    public List<AuditEntryEntity> getBatchedEntries() {
        return batchedEntries;
    }

    /** The deterministic createdAt the probe stamps its specimens with (keeps page order stable). */
    public void stampHistory(Instant createdAt, Instant updatedAt) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
