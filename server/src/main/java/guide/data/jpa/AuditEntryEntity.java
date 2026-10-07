package guide.data.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The audit_log table (V2) as an entity — the CHILD side of this topic's one
 * association. Two columns teach two opposite identity stories from topic 16:
 * id is bigserial (@GeneratedValue IDENTITY — arrival order IS the meaning
 * here, the one row type a sequence is RIGHT for), while entity_id is the
 * loose polymorphic pointer a shared ledger needs (no FK on purpose, quoted
 * in V2's own comments).
 *
 * payload is jsonb mapped as a String: @JdbcTypeCode(SqlTypes.JSON) tells
 * Hibernate 7 the column is a JSON document, not varchar — the driver ships
 * the text and PostgreSQL parses it, exactly what a flexible after-image
 * wants. The probe writes its specimen rows through this mapping; topic 17's
 * JdbcTemplate path writes the REAL ones.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntryEntity {

    /** The ledger's own entity-type word for "this row is about a post" — matches @SQLRestriction on PostEntity. */
    public static final String ENTITY_TYPE_POST = "post";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "actor", nullable = false, length = 80)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected AuditEntryEntity() {
    }

    public AuditEntryEntity(String entityType, UUID entityId, Instant changedAt, String actor, String payload) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.changedAt = changedAt;
        this.actor = actor;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getActor() {
        return actor;
    }

    public String getPayload() {
        return payload;
    }
}
