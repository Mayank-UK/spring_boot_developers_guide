package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 18 — JPA, Hibernate, and Spring Data. Group data, order 3. The
 * guide's ORM specimen: the SAME posts table topic 17 reads with
 * JdbcTemplate, mapped by an entity beside it — two implementations of one
 * contract is the honest comparison — plus the measured N+1 probe, the
 * optimistic-locking race, projections, Specifications, Page metadata with
 * its count cost, and the Boot 4 slice + boot-without-a-database gotchas.
 */
public final class SpringDataJpaTopic {

    private SpringDataJpaTopic() {
    }

    public static Topic topic() {
        return new Topic("spring-data-jpa", "JPA, Hibernate, and Spring Data",
                "An ORM does four jobs for you — identity map, dirty checking, "
                        + "unit of work, dialect — and each has a price. This topic maps the "
                        + "same posts table JdbcTemplate already reads, measures the N+1 "
                        + "problem with a real query counter, fixes it three ways, races two "
                        + "writers against one @Version column, and keeps the app booting "
                        + "with PostgreSQL down.",
                TopicGroupId.DATA, 3,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-an-orm-actually-does", "What an ORM actually does",
                        "Mapping is the boring tenth of an ORM. The other nine-tenths manage objects at runtime — and every one of those jobs has a cost you can measure.",
                        text("orm:two-jobs", """
                                An object-relational mapper does two very different jobs, and conflating them is where ORM pain starts. MAPPING is the boring part: posts.title becomes postEntity.title, text[] becomes List&lt;String&gt;, timestamptz becomes Instant. MANAGING is the interesting part: the ORM keeps loaded objects in a persistence context, decides which changed, writes changes back at flush, and speaks a SQL dialect per database. JPA is the standard API for both; Hibernate is the implementation Boot ships; Spring Data JPA generates repositories from interfaces.

                                The runtime machinery does four jobs. The IDENTITY MAP guarantees one Java instance per row inside a session — load post 42 twice, get the same object. DIRTY CHECKING diffs every managed entity against its loaded snapshot, so you mutate plain fields and the ORM writes the UPDATE. The UNIT OF WORK batches those writes into one transactional flush. The DIALECT translates criteria into each database's SQL. Each job costs something: identity maps hold memory per loaded row; dirty checking diffs every managed entity at flush, O(managed entities) even when nothing changed; flush hides WHEN writes happen; dialects shield you from the database until they generate SQL you did not expect."""),
                        code("orm:deps", "xml",
                                "server/pom.xml — the runtime starter and, FLAGGED, Boot 4's separate JPA test slice",
                                "server/pom.xml", """
                                <!-- Topic 18 (spring-data-jpa): Spring Data JPA + Hibernate. -->
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-data-jpa</artifactId>
                                </dependency>
                                <!-- Boot 4's per-technology test slices: no artifact, no @DataJpaTest. -->
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-data-jpa-test</artifactId>
                                    <scope>test</scope>
                                </dependency>"""),
                        text("orm:specimen", """
                                The specimen is deliberate: the posts table already exists, already has a hand-written adapter (PgPostRepository, topic 17), and already carries domain rules in its columns — a version column, a CHECK on status, tags as text[]. JPA maps that SAME table beside the JdbcTemplate path; the two implementations answer one contract side by side — not a toy schema built for the ORM, but a schema it must obey.

                                One Boot 4 note before any code: the runtime starter is still spring-boot-starter-data-jpa, but the TEST slice is not in spring-boot-starter-test anymore — @DataJpaTest lives in spring-boot-starter-data-jpa-test, the same modularization that moved @WebMvcTest into spring-boot-webmvc-test. Omit it and your repository tests silently do not exist."""),
                        code("orm:entity", "java",
                                "guide/data/jpa/PostEntity.java — the mapping: app-minted id, converters, @Version, listeners",
                                "server/src/main/java/guide/data/jpa/PostEntity.java", """
                                @Entity
                                @Table(name = "posts")
                                public class PostEntity {

                                    @Id
                                    @Column(name = "id", nullable = false)
                                    private UUID id;
                                    // …
                                    /** The optimistic-lock token: every UPDATE quotes it; a mismatch is a 409, not a lost update. */
                                    @Version
                                    @Column(name = "version", nullable = false)
                                    private int version;

                                    @OneToMany(fetch = FetchType.LAZY)
                                    @JoinColumn(name = "entity_id")
                                    @SQLRestriction("entity_type = 'post'")
                                    private List<AuditEntryEntity> entries = new ArrayList<>();
                                    // …
                                }"""),
                        text("orm:no-boot-dependency", """
                                Adding JPA normally adds a boot-time database dependency: Hibernate builds its EntityManagerFactory at startup, and by default that build READS JDBC METADATA — one real connection — to pick a dialect. This app's rule is that PostgreSQL can be down at boot, so the factory must build offline: name the dialect explicitly, forbid metadata access, leave the schema to Flyway. The application.yml block in the testing section shows the exact properties — including one rename that breaks older advice.""")),

                section("persistence-context-and-dirty-checking", "The persistence context and dirty checking",
                        "The EntityManager is a cache with a write-behind queue. Knowing when it loads, when it diffs, and when it flushes is the whole skill of using JPA without surprises.",
                        text("pc:lifecycle", """
                                The persistence context — the first-level cache — is the ORM's working set. A MANAGED entity came from a query or a find, is tracked by id, and every intended write flows through it. find() checks the cache first; a JPQL query always hits the database but merges rows into cached instances. DETACHED is what happens when the context closes: the object is data again, quoting the version it saw. merge() brings a detached object back by copying its state onto the managed instance — and by checking the version column first.

                                Flush is when diffs become SQL: at commit, before a query whose results could be affected (FlushModeType.AUTO), or when you call flush() yourself. Flushing does NOT commit — it ships pending SQL inside the still-open transaction, which is exactly why a later rollback undoes it. The probe below abuses that property on purpose."""),
                        code("pc:flush-clear", "java",
                                "guide/data/jpa/PgJpaProbe.java — flush ships the INSERTs, clear empties the cache, then counting starts",
                                "server/src/main/java/guide/data/jpa/PgJpaProbe.java", """
                                return transactions.execute(status -> {
                                    seedSpecimens(size);
                                    entityManager.flush();
                                    // Clear the persistence context: the measured reads must hit the
                                    // database, not the first-level cache the inserts just filled.
                                    entityManager.clear();
                                    counter.reset();
                                    // … the page query and the consume loop run here, then:
                                    status.setRollbackOnly();
                                    // …
                                });"""),
                        text("pc:dirty", """
                                Dirty checking removes the save() you never wrote. Mutate a managed entity — any field — and the next flush diffs current state against the load-time snapshot; the diff becomes an UPDATE whose WHERE includes the version column. There is no save call in classic JPA for managed entities, only "make it dirty and let flush notice". Spring Data's save() hides this: with an id it merges, without one it persists — the name lies about the mechanism.

                                The flush-forgetting story: a test mutated a managed entity, then CLEARED the context before the transaction ended — clear() drops the SNAPSHOT too, so flush had nothing to diff and shipped nothing, and the assertion read the old value. Dirty checking only works while the (instance, snapshot) pair lives in the context: mutate inside the transaction that will commit, or merge deliberately afterwards — never half-way."""),
                        code("pc:dirty-proof", "java",
                                "guide/data/jpa/PostEntityRepositoryTest.java — retitle + flush: the diff becomes UPDATE, the version becomes 1",
                                "server/src/test/java/guide/data/jpa/PostEntityRepositoryTest.java", """
                                saved.retitle("auditing — revised");
                                // save() alone does not flush; the version assertion needs the UPDATE
                                // shipped, so the dirty diff and its @Version bump are FORCED here.
                                PostEntity revised = posts.saveAndFlush(saved);
                                assertEquals(1, revised.getVersion(), "the accepted update bumped the version to 1");
                                assertTrue(revised.getUpdatedAt().compareTo(revised.getCreatedAt()) >= 0,
                                        "@PreUpdate refreshed updatedAt (>= createdAt always — the table CHECKs this)");""")),

                section("mapping-associations-fetch-shapes", "Mapping associations and fetch shapes",
                        "Every association is a loaded-now-or-later decision. The five shapes — EAGER, LAZY, JOIN FETCH, EntityGraph, BatchSize — are the same decision made at five different scopes.",
                        text("fetch:shapes", """
                                EAGER loads the association with its owner, always, whether or not anything reads it. LAZY loads it on first touch — the default for @OneToMany and @ManyToOne in Hibernate, precisely because EAGER everywhere is how applications load graphs nobody asked for. JOIN FETCH is a per-QUERY instruction: this statement fetches owner and association in one SELECT. @EntityGraph says the same thing attached to a repository method. @BatchSize is the Hibernate compromise: keep it lazy, but when you do initialize, initialize up to N in one round trip.

                                The wrong-but-common mapping is @ManyToOne(fetch = EAGER) because it makes tests pass without a transaction; it also makes every listing pay for the association. This guide's default is LAZY everywhere, with fetch decisions made PER READ at the query level — the repository carries the same page in three shapes and the endpoint picks. The entity describes STRUCTURE; queries describe FETCHING — the separation the N+1 section measures."""),
                        code("fetch:mapping", "java",
                                "guide/data/jpa/PostEntity.java — one association, mapped twice: the probe's A/B rig",
                                "server/src/main/java/guide/data/jpa/PostEntity.java", """
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
                                private List<AuditEntryEntity> batchedEntries = new ArrayList<>();"""),
                        text("fetch:no-fk-join", """
                                The association joins two tables with NO foreign key between them, and that deserves a hard look. audit_log (topic 16's V2) is a polymorphic ledger: entity_type plus entity_id point at any table's row, so an FK is impossible by design. JPA does not require one — @JoinColumn names the logical join column, and @SQLRestriction adds the clause the FK would have encoded: only ledger rows whose entity_type is 'post' belong to this collection. The lesson generalizes: the ORM happily maps relationships the database cannot constrain, so the mapping's comments must carry the honesty the schema cannot.

                                And yes — the association is mapped TWICE, once plain lazy and once with @BatchSize. That is the probe's instrument rig, not a pattern to copy: a real model picks ONE fetch shape, the demo needs both against the same rows to measure the difference. Flagged here so nobody duplicates it into a production entity."""),
                        code("fetch:converter", "java",
                                "guide/data/jpa/StringListConverter.java — List<String> over text[], the only bridge",
                                "server/src/main/java/guide/data/jpa/StringListConverter.java", """
                                @Converter
                                public final class StringListConverter implements AttributeConverter<List<String>, String[]> {

                                    @Override
                                    public String[] convertToDatabaseColumn(List<String> attribute) {
                                        return attribute == null ? new String[0] : attribute.toArray(new String[0]);
                                    }

                                    @Override
                                    public List<String> convertToEntityAttribute(String[] dbData) {
                                        return dbData == null ? List.of() : List.of(dbData);
                                    }
                                }"""),
                        text("fetch:converter-why", """
                                Dissect the tags converter, because it shows how converters slot into Hibernate's type system. Since Hibernate 6, the ORM maps JAVA ARRAYS to SQL arrays natively on PostgreSQL — String[] really becomes text[]. It knows nothing about List&lt;String&gt;, so the converter's job is narrow: translate between the field's type (List, the domain shape everywhere here) and a database representation Hibernate already maps (String[]). Null and empty are deliberately conflated because the column is NOT NULL DEFAULT '{}'.

                                Status gets a converter for a different reason — the one every enum-over-a-legacy-schema hits. @Enumerated(EnumType.STRING) writes the CONSTANT name ('DRAFT'), but the table CHECKs for the wire words 'draft'/'published', so every insert dies. ORDINAL stores 0/1 and reorders your world the day someone re-sequences the enum. A three-line converter maps wireName() both ways — the same translation PgPostRepository's RowMapper did by hand. Rule of thumb: ORDINAL never; STRING when you own the spelling; a converter when the database already chose one.""")),

                section("repositories-derivation-and-query", "Repositories: derivation and @Query",
                        "Spring Data turns one interface into a working repository — method names derive queries, @Query writes them out, and the return type decides the shape you get back.",
                        text("repo:proxy", """
                                A Spring Data repository is an interface you never implement: at startup the framework derives SQL from method names and registers a proxy that executes them. The name is a small grammar — findBy / countBy / existsBy, property paths, operators (StartingWith, Containing, In, Between), then OrderBy — and the parameters line up with the name's parts: findByTitleStartingWith(prefix, sort) becomes SELECT ... WHERE title LIKE ? with your ORDER BY. Derivation is great until two or three conditions make the name unreadable; joins need JPQL, and runtime-decided filter sets need Specifications — the next sections carry those.

                                The return type is half the contract: Optional&lt;T&gt; for at-most-one, List&lt;T&gt; for collections, Page&lt;T&gt; when you accept the count-query cost, Slice&lt;T&gt; when you only need hasNext (no count), long for counts. Choosing Page casually is the classic tax: every page costs its content SELECT plus a COUNT — the pagination section measures that two-statement bill exactly."""),
                        code("repo:derived", "java",
                                "guide/data/jpa/PostEntityRepository.java — derivation, an aggregate, and an interface projection",
                                "server/src/main/java/guide/data/jpa/PostEntityRepository.java", """
                                public interface PostEntityRepository
                                        extends JpaRepository<PostEntity, UUID>, JpaSpecificationExecutor<PostEntity> {

                                    /** The probe's page key: LIKE 'prefix%' with a deterministic order — derived, zero annotations. */
                                    List<PostEntity> findByTitleStartingWith(String prefix, Sort sort);

                                    /** A derived aggregate — count(*) where status = ?, no JPQL needed. */
                                    long countByStatus(PostStatus status);

                                    /** The interface-projection specimen: same page as entities, but only the named properties leave the database. */
                                    Page<PostSummary> findByStatus(PostStatus status, Pageable pageable);
                                    // …
                                }"""),
                        text("repo:query", """
                                @Query takes over when the name grammar runs out. JPQL talks in entities and paths — select p from PostEntity p — never table names, and Hibernate compiles it at startup, so a typo fails context load instead of a 3am request. Two variants matter here: left join fetch p.entries ships owner and association in one statement (the N+1 cure measured next), and select new guide.data.jpa.PostTitleView(p.title, p.status) is a CONSTRUCTOR EXPRESSION — Hibernate fills your record straight from the row: no entity, no persistence-context entry, nothing to dirty-check. Native SQL (@Query(nativeQuery = true)) exists for what the dialect cannot express — topic 17's CTEs, window functions, tsvector work.

                                The return-shape discipline stays: the entity when you intend to WRITE it back, a projection when you intend to show it. Nothing in this repo returns an entity past the adapter — the same DTO rule every layer already follows."""),
                        code("repo:jpql", "java",
                                "guide/data/jpa/PostEntityRepository.java — @Query JPQL, JOIN FETCH, @EntityGraph, constructor expression",
                                "server/src/main/java/guide/data/jpa/PostEntityRepository.java", """
                                /** Same page as above, but the entries association loads in the SAME statement. */
                                @EntityGraph(attributePaths = "entries")
                                List<PostEntity> findWithEntriesByTitleStartingWith(String prefix, Sort sort);

                                /** JOIN FETCH, the explicit one-query page: posts AND their entries in a single round trip. */
                                @Query("select distinct p from PostEntity p left join fetch p.entries "
                                        + "where p.title like :prefix order by p.createdAt asc")
                                List<PostEntity> findPageWithEntriesJoinFetch(@Param("prefix") String prefix);

                                /** Constructor-expression projection, paged: reading titles WITHOUT loading entities. */
                                @Query(value = "select new guide.data.jpa.PostTitleView(p.title, p.status) "
                                        + "from PostEntity p order by p.createdAt desc",
                                        countQuery = "select count(p) from PostEntity p")
                                Page<PostTitleView> findTitleViewPage(Pageable pageable);""")),

                section("n-plus-one", "The N+1 problem, measured",
                        "One query for the page, one more per row for the lazy association. It is the most famous ORM failure because it is invisible until production — unless you count statements.",
                        text("n1:mechanics", """
                                The mechanics are worth spelling out, because the fix only makes sense against them. A page query loads N posts — one SELECT, feels fine. The posts' entry lists are LAZY: promises, not data. Then the code iterates the page and touches each post's entries — rendering, counting, anything — and each touch pays one more SELECT for that row's association. N posts, N extra round trips, every request: 1 + N. Hibernate did exactly what the mapping told it to; the code asked for data row-by-row and the database answered row-by-row. Nothing in any single query looks wrong — which is why EXPLAIN never catches it and a statement counter catches it instantly.

                                The demo measures it live: it seeds N specimen posts (three audit rows each), rolls everything back afterwards, brackets the read phase with a thread-local statement counter, and runs the same page under four fetch shapes:"""),
                        code("n1:loop", "java",
                                "guide/data/jpa/PgJpaProbe.java — the consume loop where the storm happens",
                                "server/src/main/java/guide/data/jpa/PgJpaProbe.java", """
                                int entriesLoaded = 0;
                                for (PostEntity post : page) {
                                    // The consume loop is where lazy loading happens: N_PLUS_ONE
                                    // pays one SELECT per row, BATCH pays one per @BatchSize(8)
                                    // group, JOIN_FETCH/ENTITY_GRAPH pay nothing extra here.
                                    entriesLoaded += (mode == ProbeMode.BATCH
                                            ? post.getBatchedEntries() : post.getEntries()).size();
                                }
                                int queries = counter.issued();"""),
                        demo("n1:demo", "db:jpa-probe",
                                "The N+1 probe",
                                "Run the same five-post page under n-plus-1, join-fetch, entity-graph, and batch — the probe reports the exact query count, rows returned, and wall time for each shape."),
                        text("n1:numbers", """
                                The numbers, from the probe against real PostgreSQL at size 5: n-plus-1 issues 6 queries (the page plus one per post); join-fetch exactly 1; entity-graph exactly 1; batch 2 (the page, then the five lazy collections initialized in one @BatchSize(8) group). The cures trade differently. JOIN FETCH and @EntityGraph pay one wider query — fine for a page, explosive if someone drops the WHERE. @BatchSize keeps round trips small without one giant join — right when rows are processed in clumps. And the honest first question is whether the association belongs in the read at all: a projection that does not select it costs zero extra queries, the number no fix can beat.

                                That is the real lesson: the fix is a DATA-SHAPE decision, not an annotation hunt. Every row, every read — fetch the association in the page query. Sometimes, or a clump at a time — batch it. Rarely — lazy is correct and the consumer must be deliberate about paying. The probe exists because this decision deserves a number, and the number deserves an instrument:"""),
                        code("n1:counter", "java",
                                "guide/data/jpa/JpaQueryCounter.java — StatementInspector + ThreadLocal: the honest instrument",
                                "server/src/main/java/guide/data/jpa/JpaQueryCounter.java", """
                                public final class JpaQueryCounter implements StatementInspector {

                                    private final ThreadLocal<Integer> issued = ThreadLocal.withInitial(() -> 0);

                                    @Override
                                    public String inspect(String sql) {
                                        issued.set(issued.get() + 1);
                                        return sql;
                                    }
                                    // …
                                }"""),
                        text("n1:instrument-honesty", """
                                Counting honestly has rules, and the probe states them in code. StatementInspector sees every statement the SESSION prepares, so the counter is thread-local — a demo claiming "this page cost N queries" must not count another request's statements. The reset happens right after seeding and clearing, so INSERTs and the flush never pollute the measurement; only the read phase counts. And the specimens live inside a transaction marked rollback-only, so the measurement leaves no rows behind — reproducible because the world it measured vanished cleanly. A performance claim you cannot reproduce this way is an anecdote."""),
                        text("n1:client", """
                                The client console renders exactly the server's record — one typed shape from the adapter's report to the React table, no recomputation. The queries headline is the lesson; wall time is deliberately decoration, because nanoseconds on a shared demo container measure the machine, while the QUERY COUNT measures the code:"""),
                        code("n1:client-code", "ts",
                                "client/src/ui/demos/db/jpa_probe_demo.tsx — the report the client renders, verbatim from the port's record",
                                "client/src/ui/demos/db/jpa_probe_demo.tsx", """
                                interface ProbeReport {
                                  mode: string;
                                  size: number;
                                  queriesIssued: number;
                                  postsReturned: number;
                                  entriesLoaded: number;
                                  wallTimeNanos: number;
                                  verdict: string;
                                }
                                // …
                                <p data-testid="jpa-probe-queries" className="font-mono text-body text-primary">
                                  {String(panel.report.queriesIssued)} queries
                                </p>""")),

                section("projections-and-dtos", "Projections and DTOs",
                        "Most reads never become writes. Loading full entities for them pays persistence-context freight for nothing — projections read the columns you need and stop there.",
                        text("proj:why", """
                                An entity carries obligations: an identity-map slot, a dirty-check snapshot, a version, lazy promises. A read that renders a table of titles pays all of that for data it will never mutate. Projections drop the obligation — the query selects specific properties, Hibernate returns exactly that, and no persistence-context entry exists.

                                Spring Data has three projection mechanisms. INTERFACE projections: declare an interface with getTitle()/getVersion(), return it from a derived method, and a runtime proxy serves the selected columns — property names do the matching. CLASS projections (constructor expressions): the JPQL names a record constructor and Hibernate builds it from the row — type-checked at startup, and it can carry computed values, not just column echoes. DYNAMIC projections pass the projection type as a method argument — worth it when one listing serves several consumers from one method."""),
                        code("proj:interface", "java",
                                "guide/data/jpa/PostSummary.java — the interface projection: name-matched getters, no entity",
                                "server/src/main/java/guide/data/jpa/PostSummary.java", """
                                public interface PostSummary {

                                    String getTitle();

                                    PostStatus getStatus();

                                    int getVersion();
                                }"""),
                        code("proj:class", "java",
                                "guide/data/jpa/PostTitleView.java — the class projection: a record the query constructs",
                                "server/src/main/java/guide/data/jpa/PostTitleView.java", """
                                // … the javadoc above explains: type-checked at startup, may carry derived values
                                public record PostTitleView(String title, PostStatus status) {
                                }"""),
                        text("proj:choosing", """
                                Choose by what the caller does. Writes, version checks, optimistic locking — entities, because dirty checking and @Version only work on managed instances. Lists, exports, API rows — projections, because nobody renders a dirty-check snapshot. The pagination demo runs both flavors: projection=interface pages entities through a Specification and maps rows in the adapter; projection=class pages them through a constructor expression, and the report labels which shape answered. One caution: interface projections on @Query JPQL need aliases matching the getter names (p.title as title) — the derived path handles it for you, another reason derived-plus-projection is the default pairing here.""")),

                section("specifications-dynamic-queries", "Specifications: dynamic queries done safely",
                        "A search screen's filter set is decided at runtime. String-building the WHERE is how SQL injection and plan-cache pollution happen; the criteria tree is the typed alternative.",
                        text("spec:problem", """
                                Topic 17's listing built its WHERE by hand: structure composed from fixed fragments, values always bound — correct, and verbose. JPA's answer for the same problem is the CRITERIA API: a typed tree of predicates (builder.equal(root.get("status"), status)) that compiles to the same WHERE without string surgery. Raw criteria is famously wordy, so Spring Data wraps it in the Specification pattern: a Specification&lt;T&gt; is a function from (root, query, builder) to a Predicate — one composable fragment of WHERE, a value you can and() and or().

                                When does criteria beat the alternatives? When the PREDICATE SET is decided at runtime — optional filters arriving from a request. A fixed shape belongs in a derived query; a fixed-but-complex statement in @Query; a runtime-composed shape is Specification territory. The pagination endpoint draws exactly this boundary: no filter arrives, it uses the neutral all(); a status arrives, it composes hasStatus. Three more filters tomorrow is three more Specification factories, not another branch of string assembly."""),
                        code("spec:code", "java",
                                "guide/data/jpa/PostSpecifications.java — composable WHERE fragments as values",
                                "server/src/main/java/guide/data/jpa/PostSpecifications.java", """
                                public final class PostSpecifications {

                                    /** The neutral element: a predicate that constrains nothing. */
                                    public static Specification<PostEntity> all() {
                                        return (root, query, builder) -> builder.conjunction();
                                    }

                                    /** status = ? — an equality the criteria builder types for you. */
                                    public static Specification<PostEntity> hasStatus(PostStatus status) {
                                        return (root, query, builder) -> builder.equal(root.get("status"), status);
                                    }
                                }"""),
                        code("spec:use", "java",
                                "guide/data/jpa/PgJpaProbe.java — composition at the call site, not another string branch",
                                "server/src/main/java/guide/data/jpa/PgJpaProbe.java", """
                                Specification<PostEntity> spec = status == null
                                        ? PostSpecifications.all()
                                        : PostSpecifications.hasStatus(status);
                                counter.reset();
                                Page<PostEntity> page = posts.findAll(spec, PageRequest.of(number, size));"""),
                        text("spec:executor", """
                                The wiring is one supertype: JpaSpecificationExecutor&lt;PostEntity&gt; gives the repository findAll(spec), findAll(spec, pageable), count(spec) — the Specification goes in as a value and comes out as SQL through the same proxy as every derived method. Two honest caveats. Criteria still cannot express everything topic 17 wrote by hand (window functions, CTEs, FTS ranking) — those stay native. And Specifications return ENTITIES by default, so a dynamic-filter read that feeds a screen usually maps entities to rows inside the adapter — which is exactly what the pagination endpoint does: mapping stays in the data layer, and the wire never sees an entity.""")),

                section("locking-optimistic-pessimistic", "Locking: optimistic and pessimistic",
                        "Two editors, one row, no coordinator. The version column dissolves the race without a lock — and the ORM writes the WHERE clause for you.",
                        text("lock:optimistic", """
                                Topic 17 enforced optimistic concurrency by hand: every UPDATE quoted the version it read — WHERE id = ? AND version = ? — and a zero-row update meant someone got there first. JPA formalizes the same discipline: map the column with @Version and every generated UPDATE and DELETE carries the version predicate automatically. The demo's runtime sequence: two independent transactions each LOAD the row (both hold version 1 — the lost-update window); the first writer saves, flushes, commits (version 2); the second writer merges its stale copy and Hibernate compares the detached version (1) against the current row (2) — mismatch, StaleObjectStateException, translated by Spring into OptimisticLockingFailureException. Nothing was overwritten; the loser found out.

                                The translation is where the error taxonomy earns its keep again: the adapter catches the Spring exception and rethrows the losing outcome as the guide's CONFLICT kind — the same 409 envelope, the same code, the same requestId logging as the hand-written path. One taxonomy, two persistence technologies."""),
                        code("lock:race", "java",
                                "guide/data/jpa/PgJpaProbe.java — two detached readers, one winner, one typed 409",
                                "server/src/main/java/guide/data/jpa/PgJpaProbe.java", """
                                PostEntity firstReader = posts.findById(id).orElseThrow();
                                PostEntity secondReader = posts.findById(id).orElseThrow();
                                firstReader.retitle(SPECIMEN_PREFIX + "locking — first writer won");
                                secondReader.retitle(SPECIMEN_PREFIX + "locking — second writer (stale)");
                                // … first saveAndFlush bumps the version to 1, then:
                                } catch (ObjectOptimisticLockingFailureException expected) {
                                    staleWriterRejected = true; // the demo's whole point
                                    rejectionType = expected.getClass().getSimpleName();
                                }"""),
                        text("lock:pessimistic", """
                                Pessimistic locking is the other family: take a real row lock and hold it — SELECT ... FOR UPDATE — so the second writer WAITS instead of losing. JPA spells it LockModeType: PESSIMISTIC_WRITE (the classic FOR UPDATE), PESSIMISTIC_READ (weaker; PostgreSQL implements both alike), and FORCE_INCREMENT (bump the version AT READ time, catching conflicts even when a reader changed nothing). The trade is latency and deadlock surface: every lock held is a queue someone stands in. Optimistic costs the loser a RETRY; pessimistic costs everyone a WAIT.

                                This guide stays optimistic by default — it matches HTTP's shape, where a stale writer can answer 409 and let the caller re-read and re-submit. Reach for pessimistic only when losing is expensive (a hotspot row where retries storm: counters, inventory at a flash sale — topic 17's oversell race made exactly this choice) and the critical section is short. Either way the version column stays mapped: FORCE_INCREMENT and even diagnostics lean on it.""")),

                section("auditing", "Auditing: created and updated, stamped",
                        "Who set createdAt? Doing it in every write path is a bug factory. Stamping belongs in one place — the entity listener is the smallest place that works everywhere.",
                        text("audit:options", """
                                Every table here carries created_at/updated_at (timestamptz — topic 16's rule), so the question is only WHERE they get set. Three answers exist. Set them in constructors and every non-JPA writer forgets. Spring Data auditing — @EnableJpaAuditing plus @CreatedDate/@LastModifiedDate and @EntityListeners(AuditingEntityListener.class) — a context-wide facility with an audit-aware clock. Or plain JPA ENTITY LISTENERS: @PrePersist and @PreUpdate callbacks on the entity itself.

                                This guide picks the listeners: a listener is part of the ENTITY, so it fires in every context — the full app, the @DataJpaTest slice, a bare EntityManager in a test — with zero configuration and nothing to forget enabling. The guard matters too: stamp only when the field is EMPTY, so re-saving a row whose history came from elsewhere (the seed's hardcoded 2026-09 dates) can never rewrite the past."""),
                        code("audit:listeners", "java",
                                "guide/data/jpa/PostEntity.java — @PrePersist stamps birth, @PreUpdate stamps every accepted change",
                                "server/src/main/java/guide/data/jpa/PostEntity.java", """
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
                                }"""),
                        text("audit:not-the-ledger", """
                                Be precise about what these columns are NOT: an audit TRAIL. updated_at answers "when was this row last touched" — hygiene, one slot, overwritten every write. Topic 17's audit_log answers "who changed WHAT, from what, to what, when" — forensics, append-only, one row per change, written in the same transaction as the change. The two solve different problems: timestamp columns cannot tell you the previous value; a ledger cannot cheaply answer "last touched". Real systems that need both keep both — and the entity participates in the ledger too: the probe's association maps audit_log as the child side of posts, so a post's history is one lazy collection away.""")),

                section("pagination-and-sorting", "Pagination and sorting",
                        "Page metadata is a contract: totalElements, totalPages, hasNext. It costs a COUNT query per page — measure it, then decide whether offset is even the right promise.",
                        text("page:mechanics", """
                                Spring Data paging is three types. PageRequest.of(page, size, sort) is the request; Page&lt;T&gt; is the response — content plus totalElements, totalPages, first/last; Sort.by(direction, "createdAt") is the ordering. The part worth internalizing is what Page costs: TWO statements per page — the content SELECT with LIMIT/OFFSET, and a COUNT over the same WHERE for the totals. The demo's pagination endpoint reports both numbers side by side (queriesIssued: 2), because the cost is invisible until someone pages through a big table with an expensive filter and the count becomes the slow half of every page.

                                Slice&lt;T&gt; exists for that complaint: it checks hasNext by fetching size+1 rows and skips the COUNT — right for infinite scroll, wrong for "page 3 of 41" UIs."""),
                        code("page:code", "java",
                                "guide/data/jpa/PgJpaProbe.java — a real paged read, its count cost measured",
                                "server/src/main/java/guide/data/jpa/PgJpaProbe.java", """
                                counter.reset();
                                Page<PostEntity> page = posts.findAll(spec, PageRequest.of(number, size));
                                int queries = counter.issued();
                                List<PageRow> rows = page.map(post -> new PageRow(
                                        post.getTitle(), post.getStatus().wireName(), post.getVersion())).getContent();"""),
                        text("page:offset-honesty", """
                                Everything topic 17 said about offset pagination still applies, now generated by the ORM: OFFSET n is a promise the database keeps by SCANNING past n rows (deep pages slow down), and page boundaries DRIFT when rows are inserted between fetches. Hibernate emits the same LIMIT/OFFSET, so the honesty must come from API design: stable orderings (deterministic timestamps, tie-break keys), bounded depth where it matters, and keyset pagination (WHERE (created_at, id) &lt; (:lastSeenAt, :lastSeenId) ORDER BY ... LIMIT n) when consumers need stable pages — native-SQL or Specification territory, since JPQL row-value constructors are thinly supported. Sort parameters get the same discipline as ever: typed enum values at the edge, never raw column names — the property path in Sort.by is adapter-owned vocabulary, exactly like topic 17's PostSort-to-column switch.""")),

                section("testing-jpa", "Testing JPA",
                        "Three tiers, three questions: does the mapping work (slice), does the behavior work (use cases), do the numbers hold (integration). Each tier fails differently, so each earns its own test.",
                        text("test:slice", """
                                The @DataJpaTest slice boots exactly the JPA autoconfiguration — repositories, an EntityManager, transaction rollback per test — and nothing else. Against PostgreSQL via @ServiceConnection, because a project that ships one dialect should test one dialect (H2's "compatibility" is a different database — this repo's Phase 6 decision). replace = NONE stops the slice swapping in an embedded database. The tests then read like what they are — mapping contracts: converters round-trip (null and empty tags included), status stores the wire word the CHECK demands, listeners stamp, @Version increments, @EntityGraph and JOIN FETCH bring the association along, a stale detached save is rejected.

                                Boot 4 pinned two gotchas here. @DataJpaTest lives in spring-boot-starter-data-jpa-test (@AutoConfigureTestDatabase in spring-boot-jdbc-test) — the classic test starter carries none of it, and the failure mode of forgetting is silent. And the slice's autoconfigure set no longer runs FLYWAY — so this suite pays the schema bill the app's way, SchemaMigrator programmatically in @BeforeEach:"""),
                        code("test:slice-code", "java",
                                "guide/data/jpa/PostEntityRepositoryTest.java — the slice, pinned to real PostgreSQL",
                                "server/src/test/java/guide/data/jpa/PostEntityRepositoryTest.java", """
                                @Testcontainers
                                @DataJpaTest
                                @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
                                class PostEntityRepositoryTest {

                                    @Container
                                    @ServiceConnection
                                    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
                                    // …
                                }"""),
                        text("test:tiers", """
                                Repository tests are not use case tests. The slice never loads RunJpaProbe or the scenario seam — the use cases get plain unit tests for validation (bounds, wire words, defaults) with a stubbed port, no Spring at all. And the numbers only exist at integration tier: the full context, a real container, MockMvc through HTTP, assertions on measured counts — inequalities where stability matters (n+1 issues more than size), exact where the mechanics are deterministic (join-fetch: exactly one). Brittle tests pinning today's plan cache document the wrong thing; the inequality documents the disease.

                                One test this topic had to keep green, and it is the boot gotcha in config form: AppBootsWithoutPostgresTest — the whole context boots with PostgreSQL unreachable. JPA nearly broke it: Hibernate's factory bootstrap wants a connection to resolve its dialect. The three-property fix keeps the factory offline:"""),
                        code("test:yaml", "yaml",
                                "server/src/main/resources/application.yml — the three settings that keep Hibernate offline at boot",
                                "server/src/main/resources/application.yml", """
                                jpa:
                                  database-platform: org.hibernate.dialect.PostgreSQLDialect
                                  open-in-view: false
                                  hibernate:
                                    ddl-auto: none
                                  properties:
                                    hibernate:
                                      boot:
                                        allow_jdbc_metadata_access: false"""),
                        text("test:yaml-why", """
                                Read that block as a checklist; each line kills a specific connection attempt. database-platform names Hibernate 7's dialect outright (org.hibernate.dialect.PostgreSQLDialect — Hibernate 7.4.5 is what Boot 4.1.1 ships), so dialect resolution needs no metadata round trip. hibernate.boot.allow_jdbc_metadata_access: false forbids JDBC metadata reads during bootstrap entirely — and NOTE THE KEY: the classic advice says hibernate.temp.use_jdbc_metadata_defaults=false; that key is GONE from Hibernate 7 (verified against the 7.4.5 jar's JdbcSettings). Old blog posts are why apps still connect. ddl-auto: none keeps the schema with Flyway — including "validate", which sounds free and ALSO grabs a connection. open-in-view: false is hygiene: no lazily-held persistence context across view rendering. The test boots, the pool stays empty, the first live JPA call pays the connection — degrade-gracefully intact.""")),

                section("terminal-companion", "Terminal companion",
                        "The probe, the race, and the page — driven by curl, numbers on the record. Every claim above is one request away.",
                        text("terminal:setup", """
                                The JPA demo area starts FAKE, and its fake answer is deliberately a typed envelope, not silence: call it before activating and you get a 503 naming the 'jpa' area and the exact PATCH that activates it — the same degrade-gracefully contract every data area honors. Activate, then measure (server on localhost:8080):"""),
                        code("terminal:activate", "bash",
                                "Activate the jpa area — the fake-mode envelope first, then the live flip",
                                null, """
                                # before activation: the typed envelope (503, code service_unavailable)
                                curl -s localhost:8080/api/demo/jpa/probe?mode=n-plus-1

                                # the flip: the global live mode makes the 'jpa' area live
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' \\
                                  -d '{"dataMode":"live"}'

                                # first live call pays migrate + seed once (idempotent), then measures
                                curl -s 'localhost:8080/api/demo/jpa/probe?mode=n-plus-1&size=5'"""),
                        code("terminal:modes", "bash",
                                "The four fetch shapes on the same five-post page",
                                null, """
                                for mode in n-plus-1 join-fetch entity-graph batch; do
                                  curl -s "localhost:8080/api/demo/jpa/probe?mode=$mode&size=5"
                                done
                                # n-plus-1:     6 queries — the disease
                                # join-fetch:   1 query  — cure one
                                # entity-graph: 1 query  — cure two
                                # batch:        2 queries — cure three"""),
                        code("terminal:locking", "bash",
                                "The optimistic race — the story, then the real 409",
                                null, """
                                # the story as JSON: first writer won, stale writer rejected
                                curl -s localhost:8080/api/demo/jpa/probe/locking

                                # the losing writer's outcome AS the error envelope
                                curl -i 'localhost:8080/api/demo/jpa/probe/locking?surface=envelope' \\
                                  | head -1   # HTTP/1.1 409
                                curl -s 'localhost:8080/api/demo/jpa/probe/locking?surface=envelope'
                                # → the envelope's "code" field: conflict"""),
                        code("terminal:pagination", "bash",
                                "Page metadata, the count cost, both projections",
                                null, """
                                curl -s 'localhost:8080/api/demo/jpa/probe/pagination?page=0&size=10'
                                # totalElements: 12, totalPages: 2, queriesIssued: 2 (content + count)

                                curl -s 'localhost:8080/api/demo/jpa/probe/pagination?status=draft'
                                # → totalElements 2 · "projection": "specification" (…status=draft)

                                curl -s 'localhost:8080/api/demo/jpa/probe/pagination?projection=class&size=5'
                                # → "projection": "class" (constructor expression)"""),
                        text("terminal:close", """
                                What the curl tour proves, in one paragraph: the same page costs 6 queries one way and 1 another, measured rather than asserted; a stale writer gets the same 409 envelope this guide serves everywhere, translated from a Hibernate exception through one taxonomy; a Page costs two statements and says so; and none of it required the database to be up at boot. That is JPA used deliberately — mapping what exists, fetching what is asked, counting what matters, failing in the house dialect. The next topics step away from relational storage (MongoDB, Redis) carrying the same discipline: ports, DTO boundaries, honest measurements, a boot that never waits.""")));
    }
}
