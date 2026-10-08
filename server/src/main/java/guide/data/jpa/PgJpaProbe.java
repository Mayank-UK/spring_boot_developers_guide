package guide.data.jpa;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.sqldemo.DbDemoActivation;
import guide.domain.jpa.JpaDemos;
import guide.domain.jpa.JpaProbePort;
import guide.domain.jpa.ProbeMode;
import guide.domain.posts.PostStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The topic 18 demo adapter: everything the JPA side demonstrates, measured
 * against real PostgreSQL. The 'jpa' scenario area starts FAKE and its fake
 * answer is the typed UNAVAILABLE envelope below (activate with a live flip);
 * once LIVE, the first call reuses topic 17's DbDemoActivation — migrate +
 * seed through the SAME SchemaMigrator/SeedPosts paths, not a copy.
 *
 * The probe's honesty rules, because the numbers are the lesson:
 * - specimens are seeded inside ONE transaction that is ALWAYS rolled back
 *   (setRollbackOnly before returning) — the demo leaves zero residue, and a
 *   crash mid-probe leaves none either;
 * - the specimens are flushed AND cleared out of the persistence context
 *   before counting starts, so the measured reads really hit the database
 *   instead of the first-level cache;
 * - JpaQueryCounter brackets exactly the read window: reset() before, read
 *   after, remove() in a finally — seeding inserts are never counted as page
 *   queries, and a pooled thread never inherits the last request's count.
 *
 * The locking demo needs the opposite honesty — a COMMITTED row two
 * independent transactions can fight over — so it plants one specimen,
 * commits it, runs the race, and deletes it in a finally block.
 */
public final class PgJpaProbe implements JpaProbePort {

    private static final String SPECIMEN_PREFIX = "jpa-probe specimen ";
    private static final int ENTRIES_PER_SPECIMEN = 3;

    private final ScenarioStore scenarioStore;
    private final DbDemoActivation activation;
    private final JpaQueryCounter counter;
    private final PostEntityRepository posts;
    private final TransactionTemplate transactions;

    /** The transaction-shared EntityManager (injected by @PersistenceContext, not the constructor). */
    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    public PgJpaProbe(ScenarioStore scenarioStore, DbDemoActivation activation, JpaQueryCounter counter,
            PostEntityRepository posts, PlatformTransactionManager transactionManager) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.counter = Objects.requireNonNull(counter, "counter");
        this.posts = Objects.requireNonNull(posts, "posts");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public ProbeReport probe(ProbeMode mode, int size) {
        requireLive();
        activation.ensureReady();
        return transactions.execute(status -> {
            seedSpecimens(size);
            entityManager.flush();
            // Clear the persistence context: the measured reads must hit the
            // database, not the first-level cache the inserts just filled.
            entityManager.clear();
            counter.reset();
            long start = System.nanoTime();
            // The read window: everything between reset() and issued() is the
            // measurement. remove() in the finally closes the bracket — the
            // pooled thread must not carry this count into its next request.
            try {
                List<PostEntity> page = switch (mode) {
                    case N_PLUS_ONE -> posts.findByTitleStartingWith(
                            SPECIMEN_PREFIX, Sort.by(Sort.Direction.ASC, "createdAt"));
                    case JOIN_FETCH -> posts.findPageWithEntriesJoinFetch(SPECIMEN_PREFIX + "%");
                    case ENTITY_GRAPH -> posts.findWithEntriesByTitleStartingWith(
                            SPECIMEN_PREFIX, Sort.by(Sort.Direction.ASC, "createdAt"));
                    case BATCH -> posts.findByTitleStartingWith(
                            SPECIMEN_PREFIX, Sort.by(Sort.Direction.ASC, "createdAt"));
                };
                int entriesLoaded = 0;
                for (PostEntity post : page) {
                    // The consume loop is where lazy loading happens: N_PLUS_ONE
                    // pays one SELECT per row, BATCH pays one per @BatchSize(8)
                    // group, JOIN_FETCH/ENTITY_GRAPH pay nothing extra here.
                    entriesLoaded += (mode == ProbeMode.BATCH
                            ? post.getBatchedEntries() : post.getEntries()).size();
                }
                int queries = counter.issued();
                long wallTimeNanos = System.nanoTime() - start;
                status.setRollbackOnly();
                return new ProbeReport(mode.wireName(), size, queries, page.size(), entriesLoaded,
                        wallTimeNanos, verdictFor(mode, size, queries));
            } finally {
                counter.remove();
            }
        });
    }

    @Override
    public LockingReport lockRace() {
        requireLive();
        activation.ensureReady();
        UUID id = UUID.nameUUIDFromBytes("jpa-probe-locking-specimen".getBytes());
        // Clean any residue from a run that died between commit and cleanup.
        transactions.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                posts.findById(id).ifPresent(posts::delete);
            }
        });
        PostEntity fresh = new PostEntity(id, SPECIMEN_PREFIX + "locking",
                "One committed row, two readers, one version column.", PostStatus.DRAFT, List.of("jpa", "probe"));
        transactions.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                // Birth at version 0, then one deliberate edit — Spring Data's
                // isNew() reads a primitive @Version of 0 as NEW (persist, not
                // merge), so the race must run at version >= 1 to exercise the
                // merge-time version check. The gotcha is the lesson's too.
                PostEntity born = posts.save(fresh);
                born.retitle(SPECIMEN_PREFIX + "locking — seeded");
                posts.saveAndFlush(born); // committed: version 1
            }
        });

        // Two INDEPENDENT readers load the row before either writer runs.
        // Each findById is its own read-only transaction, so both hold
        // detached instances quoting version 1 — the lost-update window.
        PostEntity firstReader = posts.findById(id).orElseThrow();
        PostEntity secondReader = posts.findById(id).orElseThrow();
        firstReader.retitle(SPECIMEN_PREFIX + "locking — first writer won");
        secondReader.retitle(SPECIMEN_PREFIX + "locking — second writer (stale)");

        boolean staleWriterRejected;
        String rejectionType = "OptimisticLockingFailureException";
        try {
            transactions.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                    posts.saveAndFlush(firstReader); // UPDATE ... WHERE id = ? AND version = 1 -> 1 row, version 2
                }
            });
            try {
                transactions.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                        posts.saveAndFlush(secondReader); // version 1 is stale now
                    }
                });
                // If we reach here @Version is not enforcing anything.
                throw AppException.of(Failure.of(FailureKind.INTERNAL,
                        "the stale writer unexpectedly won — @Version optimistic locking is not enforcing",
                        Map.of("postId", id.toString())));
            } catch (ObjectOptimisticLockingFailureException expected) {
                staleWriterRejected = true; // the demo's whole point
                rejectionType = expected.getClass().getSimpleName();
            }
        } finally {
            transactions.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                    posts.findById(id).ifPresent(posts::delete);
                }
            });
        }
        return new LockingReport(id.toString(), "first writer (fresh version)", 2, staleWriterRejected,
                rejectionType, "409 conflict envelope (code: conflict)",
                "same discipline as the JdbcTemplate path's WHERE version = ? — the ORM writes that predicate for every UPDATE");
    }

    @Override
    public PageReport page(int number, int size, PostStatus status, boolean classProjection) {
        requireLive();
        activation.ensureReady();
        if (classProjection) {
            counter.reset();
            try {
                Page<PostTitleView> page = posts.findTitleViewPage(PageRequest.of(number, size));
                int queries = counter.issued();
                List<PageRow> rows = page.map(row -> new PageRow(row.title(), row.status().wireName(), 0)).getContent();
                return report(number, size, page, queries, "class projection (constructor expression)", rows);
            } finally {
                counter.remove();
            }
        }
        // The dynamic-filter path: one Specification, composed from zero or one
        // predicate — the same problem PgPostRepository solved with a StringBuilder.
        Specification<PostEntity> spec = status == null
                ? PostSpecifications.all()
                : PostSpecifications.hasStatus(status);
        counter.reset();
        try {
            Page<PostEntity> page = posts.findAll(spec, PageRequest.of(number, size));
            int queries = counter.issued();
            List<PageRow> rows = page.map(post -> new PageRow(
                    post.getTitle(), post.getStatus().wireName(), post.getVersion())).getContent();
            String label = "specification (dynamic filter: " + (status == null ? "none)" : "status=" + status.wireName() + ")");
            return report(number, size, page, queries, label, rows);
        } finally {
            counter.remove();
        }
    }

    private PageReport report(int number, int size, Page<?> page, int queries, String projection,
            List<PageRow> rows) {
        return new PageReport(number, size, page.getTotalElements(), page.getTotalPages(),
                page.isFirst(), page.isLast(), queries, projection, rows);
    }

    /** Deterministic specimens: stable UUIDs, staggered createdAt, three audit rows each. */
    private void seedSpecimens(int size) {
        Instant base = Instant.now();
        for (int i = 0; i < size; i++) {
            UUID id = UUID.nameUUIDFromBytes(("jpa-probe-specimen-" + i).getBytes());
            PostEntity specimen = new PostEntity(id, SPECIMEN_PREFIX + i,
                    "A page row that exists only inside the probe's rolled-back transaction.",
                    PostStatus.DRAFT, List.of("jpa", "probe"));
            specimen.stampHistory(base.plusNanos(i), base.plusNanos(i));
            posts.save(specimen);
            for (int e = 0; e < ENTRIES_PER_SPECIMEN; e++) {
                entityManager.persist(new AuditEntryEntity(AuditEntryEntity.ENTITY_TYPE_POST, id,
                        base.plusNanos(i), "jpa-probe",
                        "{\"kind\":\"jpa-probe-specimen\",\"seq\":" + e + "}"));
            }
        }
    }

    private static String verdictFor(ProbeMode mode, int size, int queries) {
        return switch (mode) {
            case N_PLUS_ONE -> queries > size
                    ? "N+1 confirmed: " + size + " posts cost " + queries + " queries ("
                            + (queries - size) + " extra) — every lazy entry list was fetched alone"
                    : "no N+1 measured — unexpected at this size";
            case JOIN_FETCH -> queries == 1
                    ? "JOIN FETCH: the whole page, entries included, in ONE query"
                    : "expected one query, measured " + queries;
            case ENTITY_GRAPH -> queries == 1
                    ? "@EntityGraph: the same one-query page, declared per query instead of per mapping"
                    : "expected one query, measured " + queries;
            case BATCH -> queries < size + 1 && queries > 1
                    ? "@BatchSize(8): lazy loading kept, round trips collapsed to " + queries + " queries"
                    : "expected between 2 and " + size + " queries, measured " + queries;
        };
    }

    private void requireLive() {
        if (scenarioStore.dataModeFor(JpaDemos.AREA) != ScenarioDataMode.LIVE) {
            throw AppException.of(Failure.of(FailureKind.UNAVAILABLE,
                    "the JPA probe runs only against live PostgreSQL — the 'jpa' demo area is in fake mode; "
                            + "PATCH /api/demo/scenario {\"dataMode\":\"live\"} to activate it",
                    Map.of("dependency", "postgres", "area", JpaDemos.AREA)));
        }
    }
}
