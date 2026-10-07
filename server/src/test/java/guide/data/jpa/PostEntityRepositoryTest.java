package guide.data.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.result.Failed;
import guide.core.result.Result;
import guide.data.sql.SchemaMigrator;
import guide.domain.posts.PostStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The @DataJpaTest slice — repository-shaped tests against REAL PostgreSQL
 * (postgres:17 via @ServiceConnection, replace=NONE: no embedded swap, one
 * dialect). The slice boots only the JPA autoconfiguration: repositories +
 * an EntityManager, transaction rollback per test — no web, no scenario
 * store, no controllers.
 *
 * Boot 4 gotcha this suite pins TWICE. First: @DataJpaTest is not in
 * spring-boot-starter-test anymore — the slice annotation lives in
 * spring-boot-starter-data-jpa-test (and @AutoConfigureTestDatabase in
 * spring-boot-jdbc-test, pulled in with it); omit the artifact and the
 * tests silently do not exist. Second: the Boot 4 slice's autoconfigure
 * set NO LONGER RUNS FLYWAY — the app has flyway.enabled=false anyway, so
 * the slice pays the schema bill the APP's way instead: SchemaMigrator,
 * programmatically, idempotently, in @BeforeEach.
 *
 * Repository tests are not use case tests: just the mapped schema and the
 * query methods, asserted on behavior (round trips, stamps, version
 * arithmetic), never on SQL text. The slice's test transaction never
 * commits, so the optimistic-locking proof plays the competing writer as a
 * native UPDATE — the genuine two-instance, two-transaction race lives in
 * JpaDemoIntegrationTest, where commits are possible.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostEntityRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private PostEntityRepository posts;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void migrateSchemaAndCleanSlates() {
        Result<SchemaMigrator.MigrationSummary> migrated = new SchemaMigrator(dataSource).migrate();
        if (migrated instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
            throw new IllegalStateException("slice schema migration failed: " + failed.failure().message());
        }
        posts.deleteAll();
        entityManager.createNativeQuery("delete from audit_log").executeUpdate();
    }

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    private static PostEntity post(String title, PostStatus status, List<String> tags, long epochSeconds) {
        PostEntity p = new PostEntity(UUID.randomUUID(), title, "body of " + title, status, tags);
        // Explicit staggered history: same-instant rows would make every
        // createdAt-desc assertion a coin flip on clock precision.
        p.stampHistory(BASE.plusSeconds(epochSeconds), BASE.plusSeconds(epochSeconds));
        return p;
    }

    // ---- mapping: the converters ----

    @Test
    void tagsRoundTripThroughTheTextArrayConverterIncludingEmptyAndNull() {
        PostEntity withTags = post("tags: full", PostStatus.DRAFT, List.of("jpa", "probe", "n+1"), 3600L);
        PostEntity emptyTags = post("tags: empty", PostStatus.DRAFT, List.of(), 7200L);
        PostEntity nullTags = post("tags: null", PostStatus.DRAFT, null, 10800L);
        posts.saveAll(List.of(withTags, emptyTags, nullTags));
        entityManager.flush();
        entityManager.clear();

        assertEquals(List.of("jpa", "probe", "n+1"), posts.findById(withTags.getId()).orElseThrow().getTags(),
                "the full list survives the text[] round trip");
        assertEquals(List.of(), posts.findById(emptyTags.getId()).orElseThrow().getTags(),
                "an empty list persists as '{}' and reads back empty");
        assertEquals(List.of(), posts.findById(nullTags.getId()).orElseThrow().getTags(),
                "a null list is conflated with empty — the column is NOT NULL, the converter defends");
    }

    @Test
    void statusStoresTheWireWordNotTheEnumConstantName() {
        PostEntity draft = post("status wire", PostStatus.DRAFT, List.of(), 0L);
        posts.save(draft);
        entityManager.flush();
        entityManager.clear();

        // The V1 CHECK constraint allows only 'draft'/'published' — the stored
        // spelling must be the wire word or the insert would have died.
        Object stored = entityManager
                .createNativeQuery("select status from posts where id = :id")
                .setParameter("id", draft.getId())
                .getSingleResult();
        assertEquals("draft", stored, "the converter wrote the wire word the CHECK constraint demands");
        assertEquals(PostStatus.DRAFT, posts.findById(draft.getId()).orElseThrow().getStatus());
    }

    // ---- mapping: listener auditing + @Version ----

    @Test
    void listenerStampsCreatedAtAndUpdatedAtAtBirthAndBumpsBothOnUpdate() {
        PostEntity fresh = new PostEntity(UUID.randomUUID(), "auditing", "body", PostStatus.DRAFT, List.of());
        PostEntity saved = posts.save(fresh);
        assertNotNull(saved.getCreatedAt(), "@PrePersist stamped createdAt");
        assertNotNull(saved.getUpdatedAt(), "@PrePersist stamped updatedAt");
        assertEquals(0, saved.getVersion(), "JPA @Version starts at zero at birth");
        assertEquals(saved.getCreatedAt(), saved.getUpdatedAt(), "birth stamps both clocks identically");

        saved.retitle("auditing — revised");
        // save() alone does not flush; the version assertion needs the UPDATE
        // shipped, so the dirty diff and its @Version bump are FORCED here.
        PostEntity revised = posts.saveAndFlush(saved);
        assertEquals(1, revised.getVersion(), "the accepted update bumped the version to 1");
        assertTrue(revised.getUpdatedAt().compareTo(revised.getCreatedAt()) >= 0,
                "@PreUpdate refreshed updatedAt (>= createdAt always — the table CHECKs this)");
    }

    // ---- repositories: derived, @EntityGraph, @Query ----

    @Test
    void derivedQueriesByMethodNameAggregateAndPage() {
        posts.saveAll(List.of(
                post("alpha", PostStatus.PUBLISHED, List.of(), 3600L),
                post("beta", PostStatus.DRAFT, List.of(), 7200L),
                post("gamma", PostStatus.PUBLISHED, List.of(), 10800L)));

        assertEquals(2, posts.countByStatus(PostStatus.PUBLISHED), "countByStatus derived the count query");
        assertEquals("gamma", posts.findFirstByStatusOrderByCreatedAtDesc(PostStatus.PUBLISHED)
                .orElseThrow().getTitle(), "findFirst...OrderBy derived the newest-published lookup");

        List<PostEntity> page = posts.findByTitleStartingWith("al", Sort.by(Sort.Direction.ASC, "createdAt"));
        assertEquals(1, page.size(), "findByTitleStartingWith derived LIKE 'al%'");
    }

    @Test
    void entityGraphAndJoinFetchBothBringTheEntriesAlong() {
        UUID id = UUID.randomUUID();
        PostEntity specimen = new PostEntity(id, "graph specimen", "body", PostStatus.DRAFT, List.of("jpa"));
        specimen.stampHistory(BASE.plusSeconds(0), BASE.plusSeconds(0));
        posts.save(specimen);
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            entityManager.persist(new AuditEntryEntity(AuditEntryEntity.ENTITY_TYPE_POST, id, now,
                    "slice-test", "{\"kind\":\"slice\",\"seq\":" + i + "}"));
        }
        entityManager.flush();
        entityManager.clear();

        List<PostEntity> viaGraph = posts.findWithEntriesByTitleStartingWith("graph", Sort.by("createdAt"));
        assertEquals(1, viaGraph.size());
        assertEquals(3, viaGraph.getFirst().getEntries().size(),
                "@EntityGraph loaded the entries in the same statement round");

        List<PostEntity> viaJoinFetch = posts.findPageWithEntriesJoinFetch("graph%");
        assertEquals(1, viaJoinFetch.size());
        assertEquals(3, viaJoinFetch.getFirst().getEntries().size(),
                "JOIN FETCH loaded the entries in one query");
    }

    @Test
    void polymorphicLedgerRowsForOtherEntitiesStayOutOfTheAssociation() {
        UUID id = UUID.randomUUID();
        PostEntity specimen = new PostEntity(id, "restriction specimen", "body", PostStatus.DRAFT, List.of());
        specimen.stampHistory(BASE.plusSeconds(0), BASE.plusSeconds(0));
        posts.save(specimen);
        Instant now = Instant.now();
        entityManager.persist(new AuditEntryEntity(AuditEntryEntity.ENTITY_TYPE_POST, id, now, "a", "{}"));
        entityManager.persist(new AuditEntryEntity("inventory_item", id, now, "a", "{}"));
        entityManager.flush();
        entityManager.clear();

        List<PostEntity> loaded = posts.findByTitleStartingWith("restriction", Sort.by("createdAt"));
        assertEquals(1, loaded.getFirst().getEntries().size(),
                "@SQLRestriction(entity_type = 'post') keeps the polymorphic ledger honest");
    }

    // ---- projections ----

    @Test
    void interfaceProjectionPagesWithoutInstantiatingEntities() {
        posts.saveAll(List.of(
                post("p-one", PostStatus.PUBLISHED, List.of(), 3600L),
                post("p-two", PostStatus.PUBLISHED, List.of(), 7200L),
                post("p-three", PostStatus.DRAFT, List.of(), 10800L)));

        Page<PostSummary> published = posts.findByStatus(PostStatus.PUBLISHED, PageRequest.of(0, 2));
        assertEquals(2, published.getTotalElements(), "the projection path still computes Page metadata");
        assertEquals(2, published.getContent().size());
        assertTrue(published.getContent().stream().allMatch(row -> row.getStatus() == PostStatus.PUBLISHED),
                "getStatus() came through the converter-backed enum");

        Page<PostTitleView> titles = posts.findTitleViewPage(PageRequest.of(0, 2));
        assertEquals(3, titles.getTotalElements(), "the constructor expression counts the same rows");
        assertEquals(2, titles.getContent().size());
        assertEquals("p-three", titles.getContent().getFirst().title(),
                "newest first — the fixed query's order; PostTitleView carries no version (a read shape, not an entity)");
    }

    // ---- optimistic locking, at the repository level ----

    @Test
    void staleDetachedInstanceIsRejectedByTheVersionColumn() {
        PostEntity saved = posts.save(post("race", PostStatus.DRAFT, List.of(), 0L));
        UUID id = saved.getId();
        saved.retitle("race — seeded");
        posts.saveAndFlush(saved);
        entityManager.flush();
        assertEquals(1, posts.findById(id).orElseThrow().getVersion());

        // The slice's test transaction never commits, so a second EntityManager
        // cannot see these rows (read-committed). The stale-writer proof runs
        // INSTEAD against a native UPDATE that plays the OTHER writer — the
        // exact WHERE version = ? predicate topic 17 wrote by hand. ORDER
        // MATTERS: the native update runs FIRST (nothing dirty pending, so no
        // auto-flush races it), bumping the row to version 2 behind the
        // persistence context's back; only then does our instance go dirty.
        PostEntity staleWriter = posts.findById(id).orElseThrow();
        int otherWriterRows = entityManager.createNativeQuery(
                        "update posts set version = 2, title = ? where id = ? and version = 1")
                .setParameter(1, "race — other writer won (native)")
                .setParameter(2, id)
                .executeUpdate();
        assertEquals(1, otherWriterRows, "the other writer's version-1 update matched exactly one row");
        staleWriter.retitle("race — stale writer");

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> posts.saveAndFlush(staleWriter),
                "the stale writer (version 1) must be rejected by the version predicate, not silently applied");
        entityManager.clear();
        assertEquals("race — other writer won (native)",
                entityManager.find(PostEntity.class, id).getTitle(),
                "the first writer's change survived; nothing was lost");
    }
}
