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
 * Topic 17 — SQL with JDBC and JdbcTemplate. Group data, order 2. The guide's
 * heaviest demo topic: six live-PostgreSQL demos (injection, migrations, the
 * SQL lab, search, the oversell race, in-transaction audit), every one of
 * them running the real SQL the prose quotes — through the same JdbcTemplate
 * + HikariCP + PgJDBC stack the posts substrate has used since Phase 6.
 */
public final class JdbcSqlTopic {

    private JdbcSqlTopic() {
    }

    public static Topic topic() {
        return new Topic("jdbc-sql", "SQL with JDBC and JdbcTemplate",
                "The whole stack between your Java and your PostgreSQL — driver, pool, "
                        + "template, transaction manager, migrator — and the SQL worth doing "
                        + "by hand: injection anatomy, CTEs and window functions, query plans, "
                        + "full-text search, the oversell race under three strategies, and an "
                        + "audit trail that commits with the change it describes.",
                TopicGroupId.DATA, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("drivers-the-layers", "Drivers, pools, and the layers between",
                        "Four layers stand between your JdbcTemplate call and a row on disk. Knowing what each one owns is what makes the stack debuggable when it misbehaves.",
                        text("layers:stack", """
                                SQL in Java travels through four layers, each with exactly one job. JDBC — the java.sql interfaces — is the STANDARD: one API so the same repository code talks to any database by swapping a driver jar. PgJDBC is the IMPLEMENTATION for PostgreSQL: wire protocol, binary-safe parameter binding, SQLSTATE-to-exception translation. Your code imports java.sql and never org.postgresql — this repo enforces it mechanically (the driver is runtime-scoped; compile code cannot import it).

                                Above the driver sits HIKARICP, the connection pool. Opening a PostgreSQL connection is expensive — TCP handshake, TLS, backend process, authentication — milliseconds you pay once per POOLED connection, not once per query. The pool's discipline: fixed size, borrowed and returned, never grown in response to load (a queue of waiters is backpressure; more connections just move the contention into the database). Boot sizes it from configuration:"""),
                        code("layers:hikari", "yaml",
                                "server/src/main/resources/application.yml — the pool, sized deliberately",
                                "server/src/main/resources/application.yml", """
                                datasource:
                                  url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}
                                  username: ${SPRING_DATASOURCE_USERNAME:guide}
                                  password: ${SPRING_DATASOURCE_PASSWORD:guide}
                                  # HikariCP sized modestly: 8 connections serves the guide's demo load with
                                  # headroom (PostgreSQL's default max_connections is 100; dev tools and
                                  # Testcontainers siblings draw from the same budget).
                                  hikari:
                                    maximum-pool-size: 8"""),
                        text("layers:template", """
                                The top layer is JdbcTemplate — Spring's answer to JDBC's ceremony. Raw JDBC wants try-with-resources around connection, statement, and result set, plus the mapping loop; JdbcTemplate does all of it and leaves you the parts worth writing: the SQL, the parameters, and a RowMapper from one result-set row to one domain object. It throws a TRANSLATED exception hierarchy instead of the checked SQLException, and it never opens a connection of its own while a transaction is active — more below.

                                The pool ceiling is not trivia — this topic's own race demo depends on every racer HOLDING a connection for the span of its attempt:"""),
                        code("layers:pool-cap", "java",
                                "guide/domain/sqldemo/RunInventoryRace.java — concurrency capped at the pool, on purpose",
                                "server/src/main/java/guide/domain/sqldemo/RunInventoryRace.java", """
                                public static final int MAX_CONCURRENCY = 8;

                                // …
                                int racers = concurrency == null ? 4 : concurrency;
                                if (racers < 2 || racers > MAX_CONCURRENCY) {
                                    throw AppException.of(Failure.of(FailureKind.VALIDATION,
                                            "concurrency must be between 2 and " + MAX_CONCURRENCY
                                                    + " — every racer holds a pooled connection for the whole race"));
                                }"""),
                        text("layers:lazy", """
                                One property of this stack matters more than any other here: it is LAZY. The DataSource connects on first BORROW, and the SQL-side beans are @Lazy — so the application boots and serves its in-memory surfaces with PostgreSQL down, and the first live call pays the connection cost. The degrade-gracefully rule from Phase 6; you will meet it again in the migrations section.""")),
                section("the-dao-pattern-with-jdbctemplate", "The DAO pattern, with JdbcTemplate",
                        "PgPostRepository is the specimen: a port in front, SQL constants at the top, a RowMapper as the only place columns become fields, and values that only ever travel as bind parameters.",
                        text("dao:port", """
                                The pattern has three moving parts. The PORT — an interface the domain owns — declares what persistence means; the ADAPTER implements it against a real database; everything above the port never learns which storage answered. This repo has run that promise since Phase 6: the same five PostRepository methods are answered by the in-memory fake or PgPostRepository depending on a scenario flag, and no caller changed a line. The port first, because it is the contract everything else trusts:"""),
                        code("dao:port-code", "java",
                                "guide/domain/posts/PostRepository.java — the contract: five methods, a tri-state update, an area id",
                                "server/src/main/java/guide/domain/posts/PostRepository.java", """
                                public interface PostRepository {

                                    /** Identifies this context's data adapters in the ScenarioStore's per-area mode registry. */
                                    String AREA = "api";
                                    // …"""),
                        text("dao:mapper", """
                                Inside the adapter, the ROWMAPPER is the hinge: one lambda from a JDBC row to an immutable domain object, defined once and reused by every read. Every type conversion the table needs happens here and only here — uuid to string, wire status word to enum, text[] to List&lt;String&gt;, timestamptz to Instant — so a schema change is a one-place change:"""),
                        code("dao:mapper-code", "java",
                                "guide/data/posts/PgPostRepository.java — the RowMapper: the only place columns become fields",
                                "server/src/main/java/guide/data/posts/PgPostRepository.java", """
                                /** One row -> one immutable Post; the ONLY place columns become fields. */
                                private static final RowMapper<Post> POST_MAPPER = (rs, rowNum) -> new Post(
                                        rs.getObject("id", UUID.class).toString(),
                                        rs.getString("title"),
                                        rs.getString("body"),
                                        tagsOf(rs.getArray("tags")),
                                        guide.domain.posts.PostStatus.fromWire(rs.getString("status")),
                                        rs.getLong("version"),
                                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                                        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());"""),
                        text("dao:structure-vs-values", """
                                Dynamic SQL is where DAOs go wrong, so look hard at how the listing builds its WHERE clause. There are exactly two kinds of SQL text in PgPostRepository: STRUCTURE — fixed fragments selected by code — and VALUES — everything else, which ALWAYS travels as a bind parameter. The filter composes structure from constants and appends placeholders; the values arrive in a parallel list, one ? at a time:"""),
                        code("dao:where", "java",
                                "guide/data/posts/PgPostRepository.java — structure composed from fixed fragments; values always bind",
                                "server/src/main/java/guide/data/posts/PgPostRepository.java", """
                                // Structure is composed (fixed fragments only); values always bind.
                                StringBuilder where = new StringBuilder(" WHERE TRUE");
                                List<Object> filterArgs = new ArrayList<>();
                                if (filter.status() != null) {
                                    where.append(" AND status = ?");
                                    filterArgs.add(filter.status().wireName());
                                }
                                // …
                                Long total = jdbc.queryForObject(
                                        "SELECT count(*) FROM posts" + where, Long.class, filterArgs.toArray());"""),
                        text("dao:pagination", """
                                That listing is TWO statements by design — a COUNT over the filter, then the LIMIT/OFFSET page — and the reason is a saved lesson. The one-statement alternatives (COUNT(*) OVER(), or the total inside a CTE) share a subtle flaw: the total rides RESULT ROWS, so a beyond-range page — ?page=99 on twelve posts — serves zero rows and reports NO total, drifting from the honest "0 items, 12 total" the fake adapter promises. Two queries is the shape that cannot lie; the sql-by-hand section reopens this as a story.

                                The ORDER BY is the last discipline: clients send ?sort=title,desc as TEXT, but no client text reaches the query — the field was parsed into a typed enum upstream, and the switch maps enum to column names the adapter owns:"""),
                        code("dao:orderby", "java",
                                "guide/data/posts/PgPostRepository.java — the whitelisted ORDER BY: column names come only from the typed enum",
                                "server/src/main/java/guide/data/posts/PgPostRepository.java", """
                                String column = switch (sort.field()) {
                                    case TITLE -> "LOWER(title) COLLATE \\"C\\"";
                                    case CREATED_AT -> "created_at";
                                    case UPDATED_AT -> "updated_at";
                                };
                                String direction = sort.descending() ? "DESC" : "ASC";
                                return column + " " + direction + ", id " + direction;""")),
                section("parameterized-queries-or-else", "Parameterized queries — or else",
                        "The most exploited bug class in the history of databases, rendered in one sandbox: the same attack payload through a bind parameter and through string concatenation.",
                        text("safety:anatomy", """
                                SQL injection is one sentence: input becomes SQL STRUCTURE when you meant it to be a VALUE. The vulnerable form is text like "SELECT … WHERE username = '" + input + "'" — the attacker's input is now inside your query, free to close the string you opened and append whatever it likes. The classic bypass needs no creativity: ' OR '1'='1 turns WHERE username = '<input>' into WHERE username = '' OR '1'='1' — always true — and EVERY row comes back, password column included.

                                The fix is not escaping or sanitizing input — it is the BIND PARAMETER. A PreparedStatement ships SQL text and values in SEPARATE parts of the message; the database compiles the structure once and treats each parameter as an opaque value, never tokens to parse. The payload above, bound, is just a very strange username that matches nobody. Both doors, side by side:"""),
                        code("safety:both-paths", "java",
                                "guide/data/sqldemo/PgSqlSafetySandbox.java — the safe constant, and the deliberate concat beside it",
                                "server/src/main/java/guide/data/sqldemo/PgSqlSafetySandbox.java", """
                                private static final String PARAMETERIZED_SQL =
                                        "SELECT username, role, secret FROM demo_injection WHERE username = ?";

                                // …
                                String vulnerableSql = VULNERABLE_PREFIX + payload + "'";"""),
                        text("safety:blast-radius", """
                                Run the demo and the outcomes diverge exactly as the anatomy predicts: zero rows through the bind parameter, every sandbox row (secrets included) through the concatenation. Then try a UNION escape — a payload that appends its own SELECT to read posts — and watch it die with permission denied for table posts. That is the second half of the lesson, implemented the way production should: the vulnerable statement executes on a connection authenticated as demo_sandbox, a role with ONE grant:"""),
                        code("safety:grant", "java",
                                "guide/data/sqldemo/PgSqlSafetySandbox.java — least privilege as the backstop: one role, one grant",
                                "server/src/main/java/guide/data/sqldemo/PgSqlSafetySandbox.java", """
                                private static final String GRANT_SQL = "GRANT USAGE ON SCHEMA public TO " + SANDBOX_ROLE
                                        + "; GRANT SELECT ON demo_injection TO " + SANDBOX_ROLE;"""),
                        text("safety:discipline", """
                                Bind parameters are the FIX; least privilege is the BACKSTOP — the honest assumption is that some concatenation ships anyway, and the question then is what the bug can REACH. An app account with SELECT on two tables turns a catastrophic breach into a contained, embarrassing bug. The discipline, in order: parameters for every value; whitelisted identifiers for every dynamic column or sort name (the DAO section's enum switch); least-privilege database accounts, as above.

                                One honest caveat: dynamic SEARCH terms tempt everyone into LIKE '%' + term + '%' building. The safe spelling keeps the wildcards in SQL and binds only the term — wildcards around a placeholder, never into one."""),
                        demo("safety:demo-block", "db:sql-safety",
                                "The injection showdown",
                                "Send any payload (empty runs the classic ' OR '1'='1): it executes through the bind-parameter path and through the deliberately-vulnerable concatenation path against a sandbox table with a fake secret column — and a UNION escape shows the restricted role stopping it at the table boundary."),
                        text("safety:demo-tour", """
                                In the console: run with the empty payload — the left column (bind parameter) returns zero rows with its SQL unchanged; the right column (concatenation) returns all three sandbox rows, its sqlShown field displaying the ACTUAL statement the database received, payload embedded. Now run the UNION preset: zero rows and a containmentError reading "permission denied for table posts" — the role wall, doing the job sanitization can never promise.""")),
                section("transactions-with-spring", "Transactions with Spring",
                        "@Transactional is a proxy, a thread-bound connection, and a rollback rule — three mechanics worth understanding before you trust them with your data.",
                        text("tx:mechanics", """
                                A transaction is the database's "all or nothing": BEGIN, statements, COMMIT — or ROLLBACK, and the database pretends the episode never happened. @Transactional mechanizes the bracketing: the bean is wrapped in a PROXY (the AOP topic's machinery, at work), and every EXTERNAL call into a @Transactional method gets a transaction opened, a connection BOUND TO THE THREAD, a commit on normal return — or a rollback the moment anything throwable escapes. That thread-bound connection is the invisible half: JdbcTemplate borrows the transaction's connection mid-transaction, which is why two jdbc.update calls in one method ride one atomic unit. The exhibit:"""),
                        code("tx:transfer", "java",
                                "guide/data/sqldemo/PgAuditTrail.java — two writes, one commit, one rollback rule",
                                "server/src/main/java/guide/data/sqldemo/PgAuditTrail.java", """
                                @Override
                                @Transactional
                                public void transferAndAudit(String postId, String newTitle, String changedBy, boolean failAfter) {
                                    UUID id = UUID.fromString(postId);
                                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                                    // Write 1 of 2: the business mutation (title, honest updatedAt, version bump).
                                    jdbc.update(UPDATE_POST_SQL, newTitle, now.atOffset(ZoneOffset.UTC), id);
                                    if (failAfter) {
                                        // …
                                        throw new IllegalStateException(
                                                "simulated crash between the post UPDATE and the audit INSERT — both writes roll back");
                                    }
                                    // Write 2 of 2: the trail row describing write 1 — same transaction,
                                    // so it exists if and only if the change committed.
                                    jdbc.update(INSERT_AUDIT_SQL, id, changedBy, payloadJson(newTitle, changedBy));
                                }"""),
                        text("tx:rules", """
                                Three rules decide what that proxy does. ROLLBACK: on RuntimeException and Error — including the IllegalStateException below — but NOT on checked exceptions, a default that surprises everyone once (declare rollbackFor, or stop throwing checked exceptions from transactional methods). PROPAGATION: REQUIRED (the default) joins an existing transaction or starts one; REQUIRES_NEW suspends the outer and commits its own — right for an audit row that must survive its caller's failure, and a way to exhaust the pool if nested (each suspension holds a second connection); NESTED (SAVEPOINTs) rolls back to a point INSIDE the outer transaction. SELF-INVOCATION: a @Transactional method calling another on THIS does nothing — the call never crosses the proxy; the AOP topic proved that live.

                                When the bracketing should be explicit rather than declared, Spring ships TransactionTemplate — programmatic begin/commit/rollback, no proxy. The race demo's guarded strategy uses it, because each racer thread must own its transaction from SELECT FOR UPDATE to COMMIT:"""),
                        code("tx:template", "java",
                                "guide/data/sqldemo/PgInventoryRaceTrack.java — TransactionTemplate: the bracket is code, not a proxy",
                                "server/src/main/java/guide/data/sqldemo/PgInventoryRaceTrack.java", """
                                /** SELECT FOR UPDATE inside a transaction: the lock serializes read-decide-write. */
                                private Verdict guardedAttempt() {
                                    return tx.execute(status -> {
                                        Integer stock = jdbc.queryForObject(SELECT_FOR_UPDATE_SQL, Integer.class, SKU);
                                        if (stock == null || stock < 1) {
                                            return Verdict.DECLINED;
                                        }
                                        jdbc.update(GUARDED_DECREMENT_SQL, SKU);
                                        return Verdict.RESERVED;
                                    });
                                }"""),
                        text("tx:testing", """
                                The dirty work nobody warns you about: TESTING transactions. A rollback proof cannot be asserted from inside the transaction (the in-flight view sees your own uncommitted writes), so the audit use case re-reads COMMITTED truth after the proxy finished — title unchanged, trail count unchanged, asserted against the database. The pattern: inject the failure between the writes, let the exception escape, then assert on what a FRESH query can see. And remember the pool — more concurrent transactions than HikariCP's maximum-pool-size hangs, politely, forever.""")),
                section("migrations-with-flyway", "Migrations with Flyway",
                        "Schema as versioned code: numbered SQL files, a history table that makes apply idempotent, and this repo's deliberate twist — Flyway runs programmatically, never at boot.",
                        text("migrate:model", """
                                A migration is a numbered, immutable SQL file — V1__posts.sql, V2__audit_log.sql, V10__inventory.sql — applied once, in order, forever. Flyway's entire trick is one table: flyway_schema_history records every applied version with its checksum, so "migrate" means "diff the classpath against the history, apply what is pending" — and on an up-to-date database the diff is empty, migrate() executes NOTHING, and costs one history query. Idempotence by LEDGER, not by IF NOT EXISTS spaghetti. This topic owns three of the files, V10 among them:"""),
                        code("migrate:v10", "sql",
                                "server/src/main/resources/db/migration/V10__inventory.sql — a migration teaches in comments as much as in DDL",
                                "server/src/main/resources/db/migration/V10__inventory.sql", """
                                CREATE TABLE inventory (
                                    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                    sku      text NOT NULL UNIQUE,
                                    stock    integer NOT NULL CHECK (stock >= 0),
                                    reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0)
                                );"""),
                        text("migrate:programmatic", """
                                Now the twist to question: Boot's autoconfigured Flyway runs migrations AT STARTUP — but this repo sets spring.flyway.enabled: false and runs them through SchemaMigrator, its own programmatic seam. Why refuse the default? Because the app must BOOT and SERVE with PostgreSQL down (degrade gracefully). Autoconfig Flyway would make the schema — and therefore PostgreSQL — a boot-time dependency; the programmatic seam moves the schema bill to the moment live data is first ACTIVATED, lazily, once, idempotently:"""),
                        code("migrate:yml", "yaml",
                                "server/src/main/resources/application.yml — Flyway off at boot, on purpose",
                                "server/src/main/resources/application.yml", """
                                # Flyway OFF at startup — the deliberate Phase 6 decision. Migrations run
                                # PROGRAMMATICALLY (guide.data.sql.SchemaMigrator) when the posts area first
                                # switches to live data, because the app must boot and serve with PostgreSQL
                                # unreachable (degrade gracefully): autoconfig-run Flyway at startup would
                                # make PG a boot-time dependency. Topic 17 teaches the full reasoning.
                                flyway:
                                  enabled: false"""),
                        code("migrate:seam", "java",
                                "guide/data/sql/SchemaMigrator.java — the programmatic seam: Result-typed migrate, never a throw",
                                "server/src/main/java/guide/data/sql/SchemaMigrator.java", """
                                public Result<MigrationSummary> migrate() {
                                    try {
                                        MigrateResult result = flyway().migrate();
                                        // …
                                        return Result.success(new MigrationSummary(result.migrationsExecuted, versions));
                                    } catch (RuntimeException failure) {
                                        return Result.failed(unavailableOrInternal(
                                                "migrations could not run against the configured database", failure));
                                    }
                                }"""),
                        text("migrate:activation-lead", """
                                The lazy activation that calls it — one guarded block, run on the first live call, never at boot:"""),
                        code("migrate:activation", "java",
                                "guide/data/posts/ScenarioRoutingPostRepository.java — the first live call pays the schema bill, once",
                                "server/src/main/java/guide/data/posts/ScenarioRoutingPostRepository.java", """
                                SchemaMigrator migrator = schemaMigrator.get();
                                Result<SchemaMigrator.MigrationSummary> migrated = migrator.migrate();
                                if (migrated instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
                                    throw AppException.of(failed.failure());
                                }
                                PgPostRepository pg = liveAdapter.get();
                                pg.ensureSeeded();"""),
                        text("migrate:failures", """
                                What fails, and how: an unreachable PostgreSQL is the typed UNAVAILABLE failure (503 — a dependency is down); a BROKEN MIGRATION SCRIPT is INTERNAL (500 — our bug, very different pager) — the classifier walks the cause chain for SQLSTATE class 08, the connection-exception family. And the failure every Flyway user meets once: CHECKSUM MISMATCH — edit an already-applied file and the next run refuses, because history remembers what the bytes WERE. The refusal is correct: applied migrations are immutable; the fix is always a NEW migration, never an edit."""),
                        demo("migrate:demo-block", "db:migrations",
                                "The migration ledger, live",
                                "The applied history read straight off flyway_schema_history — versions, descriptions, install times, the one-sentence state — plus an Apply button that runs SchemaMigrator.migrate() again and proves the idempotence: zero scripts executed."),
                        text("migrate:demo-tour", """
                                In the console: the ledger lists every V<n> in numeric order — V1 posts, topic 16's V2–V4, this topic's V10–V12 — each with its installed_on timestamp. Hit Apply and the response says migrationsApplied: 0, with a note explaining WHY that is the correct answer: the history table already lists everything. Nothing cached, nothing hand-maintained.""")),
                section("the-orm-comparison", "The ORM comparison",
                        "JdbcTemplate, JPA, jOOQ — control, convenience, and type-safety, each bought at a price. And the cases where raw SQL simply wins.",
                        text("orm:table", """
                                Three ways to get rows into objects, honestly compared. JDBCTEMPLATE (this topic): you write SQL, you own every query, the mapping is an explicit RowMapper — maximum control, minimum magic; you pay by hand-writing every statement and with type hazards the compiler will not catch. JPA/HIBERNATE (topic 18): entities, dirty checking, derived queries — enormous CRUD convenience; you pay with the persistence context's surprises (N+1, lazy-loading pitfalls, SQL you did not write). JOOQ: SQL as TYPE-SAFE JAVA — the compiler checks columns and types; you pay with codegen ceremony and per-database licensing.

                                The tiebreaker is WHICH WORK you are doing: reporting, analytics, batch, window functions — raw SQL wins, the query IS the product; bulk operations — SQL wins by orders of magnitude; CRUD on one aggregate with rich domain behavior — JPA earns its keep. This repo runs JdbcTemplate throughout; even inside that choice there is a spectrum of control, and the posts adapter's optimistic lock is one statement the ORM would need an annotation and a flush to express:"""),
                        code("orm:control", "java",
                                "guide/data/posts/PgPostRepository.java — the control raw SQL buys: concurrency enforcement IN the WHERE clause",
                                "server/src/main/java/guide/data/posts/PgPostRepository.java", """
                                private static final String UPDATE_SQL =
                                        "UPDATE posts SET title = ?, body = ?, status = ?, tags = ?, updated_at = ?,"
                                                + " version = version + 1"
                                                + " WHERE id = ? AND version = ?"
                                                + " RETURNING " + SELECT_COLUMNS;"""),
                        text("orm:verdict", """
                                Read that WHERE clause again: id = ? AND version = ? — optimistic concurrency enforced by the STATEMENT itself (rows-affected zero means somebody moved first; a follow-up SELECT merely classifies the loss as 404 or 409). That is "the query is the product" at CRUD scale. When topic 18 shows @Version doing the same job by annotation, notice what moved: the knowledge went from the SQL (visible, greppable, executable in psql) into framework behavior (convenient, invisible). Neither is wrong; know which you are holding. Topic 18's Spring Data repositories keep this topic's PORT shape while changing what implements it.""")),
                section("sql-by-hand", "SQL by hand: CTEs, window functions, aggregates",
                        "The query language you already have: recursive org charts, ranking without collapsing, LAG across time — over a deterministic twelve-row lab dataset, SQL beside results.",
                        text("hand:dataset", """
                                Some SQL is not plumbing between objects — it IS the feature: reports, leaderboards, time-series gaps, org charts. For that work this topic ships a LAB: a small staff dataset, three levels deep, one self-referencing manager FK, salaries in integer cents, seeded by the migration itself (V11 — the one migration here that carries data, deliberately: the lab's queries assert exact named rows, so the dataset is part of the schema's contract):"""),
                        code("hand:staff", "sql",
                                "server/src/main/resources/db/migration/V11__sql_lab.sql — the lab dataset: an org chart in one self-referencing table",
                                "server/src/main/resources/db/migration/V11__sql_lab.sql", """
                                CREATE TABLE lab_staff (
                                    id           text PRIMARY KEY,
                                    name         text NOT NULL,
                                    manager_id   text REFERENCES lab_staff (id),
                                    salary_cents integer NOT NULL CHECK (salary_cents >= 0),
                                    department   text NOT NULL,
                                    hired_on     date NOT NULL
                                );

                                INSERT INTO lab_staff (id, name, manager_id, salary_cents, department, hired_on) VALUES
                                    ('staff-001', 'Ada Lovelace',      NULL,        25000000, 'executive', '2018-01-15'),
                                    ('staff-002', 'Grace Hopper',      'staff-001', 16500000, 'platform',  '2018-03-01'),
                                    ('staff-003', 'Edsger Dijkstra',   'staff-001', 15800000, 'data',      '2018-06-11'),
                                    -- …"""),
                        text("hand:cte", """
                                THE RECURSIVE CTE is PostgreSQL's graph walk. WITH RECURSIVE names a working table, seeds it with an anchor row, then repeatedly joins it against itself until the join adds nothing — each pass one level deeper. The lab's org chart walks manager_id down from any starting manager, computing depth and a human-readable path as it goes:"""),
                        code("hand:cte-sql", "sql",
                                "guide/data/sqldemo/PgSqlLabQueries.java — the recursive walk: anchor, union, join-until-empty",
                                "server/src/main/java/guide/data/sqldemo/PgSqlLabQueries.java", """
                                WITH RECURSIVE org_chart (depth, id, name, manager_id, path) AS (
                                    SELECT 0, id, name, manager_id, name
                                    FROM lab_staff
                                    WHERE id = 'staff-002'
                                  UNION ALL
                                    SELECT o.depth + 1, s.id, s.name, s.manager_id, o.path || ' > ' || s.name
                                    FROM lab_staff s
                                    JOIN org_chart o ON s.manager_id = o.id
                                )
                                SELECT depth, id, name, path
                                FROM org_chart
                                ORDER BY depth, name"""),
                        text("hand:window", """
                                WINDOW FUNCTIONS: per-row answers over NEIGHBORHOODS of rows, without collapsing anything. GROUP BY + SUM answers "one total per department" (three rows); SUM() OVER (PARTITION BY department) answers "every employee's salary with their department's running total beside it" (twelve rows). RANK() puts each employee's pay placement on their own row; then LAG reaches BACK one row within each partition, turning hire dates into a cadence — days since the department's previous hire:"""),
                        code("hand:lag-sql", "sql",
                                "guide/data/sqldemo/PgSqlLabQueries.java — LAG over a named WINDOW: the gap column is arithmetic on the result",
                                "server/src/main/java/guide/data/sqldemo/PgSqlLabQueries.java", """
                                SELECT department,
                                       name,
                                       to_char(hired_on, 'YYYY-MM-DD') AS hired_on,
                                       to_char(lag(hired_on) OVER hire_order, 'YYYY-MM-DD') AS previous_hire_on,
                                       coalesce(hired_on - lag(hired_on) OVER hire_order, 0) AS days_since_previous_hire
                                FROM lab_staff
                                WINDOW hire_order AS (PARTITION BY department ORDER BY hired_on)
                                ORDER BY department, hired_on"""),
                        text("hand:aggregate", """
                                The fourth lab query is the classic that still trips teams: aggregate FIRST, join AFTER. The rollup wants one row per department with headcount, payroll, and the manager's name — the manager living in the SAME table, found by "the member this department's people report to". Aggregate in a subquery, then LEFT JOIN: an INNER JOIN would silently drop the executive row (the CEO reports to nobody); the LEFT keeps it with a null manager — the honest answer.

                                Now the saved lesson from Phase 6, as a story. PgPostRepository's listing was first written live with the elegant one-statement shape — total inside a CTE, page rows beside it — and every test passed, until the beyond-range page: ?page=99 on twelve posts returned ZERO rows (correct) with NO total (a lie), because the total rode rows that LIMIT had discarded. The fix was the two-statement shape — a COUNT for the total, a page query for the slice — and the comment that survives in the adapter says it best: two queries is the shape that cannot lie. And one habit from the lab adapter worth copying: no raw-SQL mode — the client picks a NAME from a whitelist enum, the SQL lives in the repo."""),
                        demo("hand:demo-block", "db:sql-lab",
                                "The SQL lab",
                                "Four named queries over the twelve-row lab dataset — recursive CTE, window ranking with running totals, LAG hire cadence, and the department rollup — each returning its own SQL beside its exact rows."),
                        text("hand:demo-tour", """
                                In the console: cte walks four rows out from Grace Hopper, paths reading "Grace Hopper > …". Window shows all twelve staff with salary_rank and running totals beside every row — Barbara Liskov at platform rank 2, Margaret Hamilton's running platform payroll 53,900,000 cents. Lag puts a number on hiring rhythm: Donald Knuth landed 140 days after Adele Goldberg. Aggregate rolls up four departments — platform 53,900,000 cents across 4 heads, the executive row's manager_name empty, exactly as the LEFT JOIN promised.""")),
                section("reading-query-plans", "Reading query plans",
                        "EXPLAIN tells you what the planner THINKS will happen; EXPLAIN ANALYZE tells you what DID. The gap between them is where slow queries hide.",
                        text("plans:explain", """
                                Every SELECT is costed: the planner estimates, per candidate path, pages touched and rows returned, picks the cheapest, and leaves its reasoning readable — EXPLAIN prints the plan; EXPLAIN ANALYZE runs it and appends ACTUAL timings and row counts. The reading skill is four tokens. COST ("cost=0.00..8.53"): startup..total in arbitrary units — compare PLANS, not absolute numbers. ROWS: the planner's ESTIMATE, the file's most valuable diagnostic — a wildly wrong one (planned 1, actually 50,000) is the root cause of most bad plans: a reasonable decision for an imaginary database. LOOPS: how many times a nested-loop node ran. NODE KINDS: Seq Scan reads the whole table; Index Scan seeks a B-tree; Bitmap Index + Heap Scan builds a bitmap of matching entries then fetches those pages — the shape GIN indexes produce.

                                Two habits: EXPLAIN BEFORE optimizing, and when rows=1 but reality returns thousands, your STATISTICS are stale — ANALYZE before blaming the planner. The search demo runs EXPLAIN live for both query styles, with one honest twist: at twelve rows the planner seq-scans EVERYTHING, and it is RIGHT — an index would cost more than the table read. To SHOW the index path anyway, the adapter explains the FTS query again with sequential scans disabled — a planner setting flipped inside a transaction and rolled back, so the pooled connection never leaks it:"""),
                        code("plans:forced", "java",
                                "guide/data/sqldemo/PgPostSearch.java — forcing the index path to surface, without leaking the setting",
                                "server/src/main/java/guide/data/sqldemo/PgPostSearch.java", """
                                boolean originalAutoCommit = con.getAutoCommit();
                                con.setAutoCommit(false);
                                try (Statement set = con.createStatement()) {
                                    set.execute("SET LOCAL enable_seqscan = off");
                                    // …"""),
                        text("plans:autopsy", """
                                A slow-query autopsy in four moves. ONE: EXPLAIN ANALYZE the offender — at twelve rows, actual time 0.3ms and the autopsy is over: nothing is slow, and the seq scan was never a problem. TWO: the same query at twelve MILLION rows — seq scan cost grows linearly, an index path logarithmically; the crossover the planner navigates by COST is why the same query gets different plans on different tables. THREE: rows= estimates — a plan that says rows=1 where reality says 40% of the table will pick a nested loop that runs a hundred thousand times; the fix is statistics, not SQL. FOUR: only now touch the query — "above department average" as a correlated subquery (one pass per employee) rewrites as a window function (one pass, period), visible as a plan shape change, not a hunch.""")),
                section("full-text-search", "Full-text search",
                        "tsvector, tsquery, and one GIN index: relevance-ranked search over words and their stems — versus LIKE, which finds substrings and nothing else.",
                        text("fts:model", """
                                LIKE '%term%' answers "is this substring anywhere in this text?" — useful, and blind: no word boundaries ('page' matches 'rampage'), no stemming ('errors' misses a document that says 'error'), no ranking, no index under a leading wildcard. Full-text search answers what users actually ask: "which documents are ABOUT these words?" Three parts. TSVECTOR: a document reduced to lexemes — to_tsvector('english', body) lowercases, strips stop-words, and STEMS each word ('errors' → 'error') so inflections match. TSQUERY: the query in that vocabulary; websearch_to_tsquery builds it from search-box syntax — words AND, "quoted phrases" stay phrases, -word excludes. MATCH: the @@ operator asks whether the vector satisfies the query; ts_rank scores HOW WELL.

                                Where the vector LIVES was decided in migration V12 — a GENERATED COLUMN: computed from title and body on every write, STORED in the row, unable to drift. The alternatives: a trigger-maintained column (more moving parts; wins only when maintenance must be conditional) or an expression index (no storage, but every rank recomputes). Weighting is the one ranking decision to make before shipping — a title hit weighs 'A', a body hit 'B', so the post whose TITLE names your term outranks one that merely mentions it:"""),
                        code("fts:migration", "sql",
                                "server/src/main/resources/db/migration/V12__search.sql — the generated column and the GIN index, DDL as documentation",
                                "server/src/main/resources/db/migration/V12__search.sql", """
                                ALTER TABLE posts
                                    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
                                        setweight(to_tsvector('english', coalesce(title, '')), 'A')
                                        || setweight(to_tsvector('english', coalesce(body, '')), 'B')
                                    ) STORED;

                                CREATE INDEX posts_search_vector_idx ON posts USING GIN (search_vector);"""),
                        text("fts:gin", """
                                GIN versus GiST is the index choice FTS forces. GIN maps each LEXEME to the rows containing it — exact, fast to query, larger, slower to update; GiST is a lossy approximation — smaller, faster-writing, false positives to recheck, and it cannot rank. Posts are written rarely and searched often: GIN, obviously. (GiST earns its keep on write-heavy text.)"""),
                        code("fts:query", "sql",
                                "guide/data/sqldemo/PgPostSearch.java — ranked search, headline excerpts, and the honest LIKE twin",
                                "server/src/main/java/guide/data/sqldemo/PgPostSearch.java", """
                                SELECT p.id::text AS id,
                                       p.title,
                                       round(ts_rank(p.search_vector, q.query)::numeric, 6) AS rank,
                                       ts_headline('english', p.body, q.query, 'MaxWords=20, MinWords=10, StartSel=<b>, StopSel=</b>') AS excerpt
                                FROM posts p, websearch_to_tsquery('english', CAST(? AS text)) AS q(query)
                                WHERE p.search_vector @@ q.query
                                ORDER BY rank DESC, p.created_at DESC
                                LIMIT ?"""),
                        text("fts:comparison", """
                                The demo runs BOTH styles on every term, and the difference is not speed (twelve rows) but MEANING: q=errors finds the post titled "One error envelope beats five correct ones" ranked FIRST (a title hit, weight 'A') and the uniform-interface post beside it (its body says "one error shape", weight 'B') — both because 'errors' and 'error' stem to the same lexeme — while ILIKE '%errors%' finds nothing, because every text says 'error'. Stemming has real edges: q=pagination does NOT match a post that only says 'paging' (stems pagin vs page). Knowing where the model draws its lines is the difference between trusting FTS and being surprised by it."""),
                        demo("fts:demo-block", "db:search",
                                "Ranked search, plans beside it",
                                "Search posts by meaning: websearch syntax in, ranked hits with <b>marked</b> excerpts out — plus the ILIKE count for the same term and three EXPLAIN readouts (default FTS, forced-index FTS, LIKE) proving the structural difference."),
                        text("fts:demo-tour", """
                                In the console: search errors — two stemmed hits, the title-weighted error-envelope post first, likeCount 0. Search "uniform interface" (quotes: a PHRASE) — the uniform-interface post ranks first on its title weight. Toggle the plans open: ftsPlan says Seq Scan (correct at this size), ftsPlanIndexForced shows the GIN index's Bitmap Index Scan, likePlan a Seq Scan with a filter — the leading wildcard's life sentence.""")),
                section("isolation-locking-and-the-race", "Isolation, locking, and the race",
                        "Four isolation levels, four anomalies they admit — and one inventory row oversold three ways: naive check-then-act, SELECT FOR UPDATE, and the atomic guarded UPDATE.",
                        text("race:isolation", """
                                Isolation levels are the database's answer to concurrent writers, each defined by the anomalies it permits. READ UNCOMMITTED admits DIRTY reads (PostgreSQL refuses; it treats it as read committed). READ COMMITTED (PostgreSQL's default): no dirty reads, but NON-REPEATABLE reads (a row re-read inside your transaction can change) and PHANTOMS (a re-run query can find new rows). REPEATABLE READ: one snapshot per transaction — those anomalies gone. SERIALIZABLE: the database proves your transactions COULD have run one at a time, aborting any that couldn't (retry required). Higher isolation buys correctness by aborting or blocking; nothing is free.

                                The oversell race is the anomaly with a price tag: one ticket left, two buyers click, both pay. The naive implementation — SELECT, check in Java, write — loses because between read and write the FACT it read expired. This repo's version binds the computed value back (SET stock = ? with read − 1), making the loss visible and unfixable by constraints: two writers both read 1, both write 0 — a PLAUSIBLE number — while reserved, the honest counter, compounds to 2. No CHECK can catch it: the value written is legal, the LIE is historical:"""),
                        code("race:naive", "java",
                                "guide/data/sqldemo/PgInventoryRaceTrack.java — the bug itself: check, gap, write-back",
                                "server/src/main/java/guide/data/sqldemo/PgInventoryRaceTrack.java", """
                                private static final String NAIVE_WRITE_BACK_SQL =
                                        "UPDATE inventory SET stock = ?, reserved = reserved + 1 WHERE sku = ?";

                                // …
                                private Verdict naiveAttempt(CyclicBarrier rendezvous) {
                                    return tx.execute(status -> {
                                        Integer stock = jdbc.queryForObject(SELECT_STOCK_SQL, Integer.class, SKU);
                                        if (stock == null || stock < 1) {
                                            return Verdict.DECLINED;
                                        }
                                        try {
                                            // …
                                            rendezvous.await(15, TimeUnit.SECONDS);
                                        } catch (Exception neverRacedCleanly) {
                                            return Verdict.DECLINED;
                                        }
                                        jdbc.update(NAIVE_WRITE_BACK_SQL, stock - 1, SKU);
                                        return Verdict.RESERVED;
                                    });
                                }"""),
                        text("race:barrier-note", """
                                A note on the barrier, because honesty matters in a teaching repo: every naive implementation HAS the gap the barrier sits in — it is where the business logic lives (a price check, an external call, one thought too many). The barrier WIDENS the gap so all racers are guaranteed to read before any racer writes; without it the same bug happens probabilistically. Deterministic here, inevitable in production.

                                The fixes. GUARDED — SELECT FOR UPDATE in a transaction: the read takes the row lock, the second racer's read WAITS and then sees post-commit truth; read-decide-write becomes one serialized step, at the price of lock-shaped waiting. ATOMIC — reach for this first: ONE statement whose WHERE carries the guard, so guard and decrement cannot be interleaved even in principle; rows-affected is the verdict:"""),
                        code("race:atomic", "java",
                                "guide/data/sqldemo/PgInventoryRaceTrack.java — the fix: guard and decrement indivisible",
                                "server/src/main/java/guide/data/sqldemo/PgInventoryRaceTrack.java", """
                                /** atomic: guard and decrement indivisible — rows-affected is the verdict. */
                                private static final String ATOMIC_GUARDED_SQL =
                                        "UPDATE inventory SET stock = stock - 1, reserved = reserved + 1 WHERE sku = ? AND stock >= 1";

                                // …
                                /** ONE statement: the WHERE guard and the decrement are indivisible. */
                                private Verdict atomicAttempt() {
                                    return jdbc.update(ATOMIC_GUARDED_SQL, SKU) > 0 ? Verdict.RESERVED : Verdict.DECLINED;
                                }"""),
                        text("race:mechanics", """
                                The mechanics under READ COMMITTED, one paragraph because they explain all three outcomes: an UPDATE touching a locked row WAITS; when the blocker commits, PostgreSQL re-evaluates the waiting UPDATE's WHERE against the NEW row version (EvalPlanQual). For the atomic path that re-evaluation is the whole fix — stock >= 1 re-checked against the version that now says 0, clean decline. For the naive path it is the whole disaster — the WHERE still matches, so the SET applies to the new version: stock blindly overwritten with the stale computed value, reserved honestly incremented on top. Same mechanism, opposite fates, decided by where the guard lives. Invariants ACROSS rows (sum of allocations ≤ budget) want SELECT FOR UPDATE on a parent row, or SERIALIZABLE with a retry loop; for "decrement if possible", the atomic UPDATE is the entire answer."""),
                        demo("race:demo-block", "db:race",
                                "The oversell race",
                                "Pick a strategy and fire N genuinely-concurrent reservation attempts at one inventory row — the scoreboard reports succeeded, oversold, final stock AND the honest reserved counter, and the story explains what the numbers mean."),
                        text("race:demo-tour", """
                                In the console: naive at concurrency 4, stock 1 — succeeded 4, oversold true, finalStock 0, finalReserved 4: four tickets sold from a shelf of one, and only reserved is telling the truth. Guarded — succeeded 1, declined 3: every decision made against post-commit reality. Atomic — the same scoreboard, zero locks-between-statements. The concurrency cap is the pool (8); try 9 and the validation error explains why — your concurrency is your pool.""")),
                section("audit-logging-in-transaction", "Audit logging, in the transaction",
                        "A trail row that exists if and only if the change it describes committed — the all-or-nothing property applied to accountability.",
                        text("audit:why", """
                                An audit trail answers, months later, WHO changed WHAT and WHEN — and it is only worth having if COMPLETE. A trail written AFTER the change commits has a hole (the change landed; the process died before the trail); written BEFORE, another (a row describing a change that rolled back — fiction in your ledger). The fix is the transactions section's whole point: write BOTH inside ONE transaction — the trail exists if and only if the change exists. One commit, two facts.

                                This demo writes into topic 16's audit_log — bigserial arrival order, a loose (entity_type, entity_id) pair with deliberately NO foreign key (an audit must describe deleted rows), a jsonb payload the writer shapes. What the exhibit doesn't show is the PROOF half — the use case reads committed truth BEFORE and AFTER, because a rollback asserted from inside the transaction would read its own uncommitted fiction:"""),
                        code("audit:proof", "java",
                                "guide/domain/sqldemo/AuditPostTransfer.java — the proof re-reads committed truth after the proxy rolled back",
                                "server/src/main/java/guide/domain/sqldemo/AuditPostTransfer.java", """
                                } catch (IllegalStateException crash) {
                                    // The crash is BETWEEN the writes; by the time we land here the
                                    // transaction has rolled back — read committed truth as the proof.
                                    String titleAfter = store.titleOf(id.toString()).orElse(null);
                                    int trailAfter = store.trailCount(id.toString());
                                    return new TransferProof(false, id.toString(), titleBefore, titleAfter, actor,
                                            trailBefore, trailAfter,
                                            "rolled back — the crash (" + crash.getMessage().replace(CRASH_MARKER + " ", "")
                                                    + ") left NO trace: title unchanged, trail row never existed");
                                }"""),
                        text("audit:shape", """
                                Two shape decisions deserve a paragraph. PAYLOAD VERSUS COLUMNS: narrow typed columns read cleanly but assume the audited shape never changes; a jsonb payload absorbs any entity's snapshot at the cost of schemaless reads — this ledger chose jsonb plus a typed core (who/when/what-entity), the fixed part answering every investigation's first three questions. APPEND-ONLY is a write-path contract no table constraint can express — the writer never UPDATEs or DELETEs; where it matters for real, a REVOKE or a raising trigger enforces what convention promises.

                                The failAfter flag makes the property observable: it throws from between the two writes, the proxy rolls everything back, and the proof returns committed: false with titleAfter equal to the ORIGINAL title and trailRowsAfter equal to trailRowsBefore — numbers read from the database after the fact. Flip it off and the same call commits both facts; the trail view reads the ledger oldest-first, fixed key order making consecutive diffs readable."""),
                        demo("audit:demo-block", "db:audit",
                                "The audited transfer",
                                "Rename a seeded post and watch the trail row land with it — then flip failAfter and watch the crash between the writes roll BOTH back, proven by before/after numbers read from committed truth. The trail view reads the ledger back."),
                        text("audit:demo-tour", """
                                In the console: run the transfer with a new title — committed: true, trailRowsBefore 0, trailRowsAfter 1, the trail view showing the payload (action title-change, actor you chose). Now with failAfter checked: committed: false, titleAfter equals the title you did NOT get, trailRowsAfter 0 — the ledger never heard about it. That symmetry — the trail knows exactly as much as the database changed, never more — is the entire pattern.""")),
                section("terminal-companion", "Terminal companion",
                        "All six demos by curl — the same endpoints the consoles drive, with responses worth reading whole.",
                        text("terminal:intro", """
                                With the stack running (server on :8080, PostgreSQL up), the tour walks every demo in order — injection first, because habit formation should hurt a little. Responses print raw and read fine; the first call pays migration + seeding for everything after it."""),
                        code("terminal:safety", "bash",
                                "the injection showdown — both doors, then the UNION escape against the role wall",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/db/sql/safety \\
                                  -H 'content-type: application/json' -d '{}'
                                # → parameterized.rows [] · vulnerable.rows [3 rows, secrets leaked]
                                #   vulnerable.sqlShown shows the payload INSIDE the WHERE clause
                                curl -s -X POST localhost:8080/api/demo/db/sql/safety \\
                                  -H 'content-type: application/json' \\
                                  -d '{"payload":"'' UNION SELECT id::text, title, body FROM posts --"}'
                                # → vulnerable.rows [] · containmentError "permission denied for table posts"
                                #   the restricted role capped the blast radius — least privilege"""),
                        code("terminal:migrations", "bash",
                                "the ledger, then the idempotent apply",
                                null, """
                                curl -s localhost:8080/api/demo/db/migrations
                                # → "N applied, 0 pending: 12" · V1 posts … V10 inventory … V12 search
                                curl -s -X POST localhost:8080/api/demo/db/migrations/apply
                                # → {"migrationsApplied":0, … "note":"idempotent by construction…"}"""),
                        code("terminal:lab", "bash",
                                "the SQL lab — named queries only, SQL beside rows",
                                null, """
                                curl -s 'localhost:8080/api/demo/db/sql/lab'
                                # → the "available" list: ["cte","window","lag","aggregate"]
                                curl -s 'localhost:8080/api/demo/db/sql/lab?query=lag'
                                # → the LAST row of "rows": Donald Knuth, hired 140 days after the department's previous hire
                                curl -s 'localhost:8080/api/demo/db/sql/lab?query=bogus'
                                # → {"error":{"code":"validation_failed", …}} — unknown names never become SQL"""),
                        code("terminal:search", "bash",
                                "search by meaning — stems versus substrings, plans beside both",
                                null, """
                                curl -s 'localhost:8080/api/demo/db/search?q=errors'
                                # → hits[].title: "One error envelope…", "The uniform interface…" · likeCount 0
                                #   stemming matched 'error' (title weight ranks first); the substring scan found nothing
                                curl -s 'localhost:8080/api/demo/db/search?q=pagination'
                                # → the "ftsPlanIndexForced" lines: "Bitmap Heap Scan on posts …",
                                #   and "Bitmap Index Scan on posts_search_vector_idx … — index-backed"""),
                        code("terminal:race", "bash",
                                "the oversell race — three strategies, one scoreboard each",
                                null, """
                                for S in naive guarded atomic; do
                                  curl -s -X POST localhost:8080/api/demo/db/race/run \\
                                    -H 'content-type: application/json' \\
                                    -d "{\\"strategy\\":\\"$S\\",\\"concurrency\\":4,\\"initialStock\\":1}"
                                done
                                # → naive:   {succeeded:4, oversold:true,  finalStock:0, finalReserved:4}
                                #   guarded: {succeeded:1, oversold:false, finalStock:0, finalReserved:1}
                                #   atomic:  {succeeded:1, oversold:false, finalStock:0, finalReserved:1}
                                #   only reserved tells the naive story — the shelf lies"""),
                        code("terminal:audit", "bash",
                                "the audited transfer — commit, then the crash that proves rollback",
                                null, """
                                POST=9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01
                                curl -s -X POST localhost:8080/api/demo/db/audit/transfer \\
                                  -H 'content-type: application/json' \\
                                  -d "{\\"postId\\":\\"$POST\\",\\"title\\":\\"Renamed by curl\\",\\"changedBy\\":\\"terminal\\"}"
                                # → committed:true · trailRowsAfter:1 — one commit, both facts
                                curl -s -X POST localhost:8080/api/demo/db/audit/transfer \\
                                  -H 'content-type: application/json' \\
                                  -d "{\\"postId\\":\\"$POST\\",\\"title\\":\\"Never sticks\\",\\"failAfter\\":true}"
                                # → committed:false · titleAfter "Renamed by curl" · trailRowsAfter:1
                                #   the crash rolled back BOTH writes; the ledger never heard a thing
                                curl -s "localhost:8080/api/demo/db/audit/trail?entityId=$POST"
                                # → the "entries" array: one entry — actor "terminal", payload {action:"title-change", …}"""),
                        text("terminal:close", """
                                The tour ends where the topic began: four layers down, every response above is JdbcTemplate handing SQL to PgJDBC over a pooled Hikari connection — the same plumbing the posts substrate has used all along, now with nothing between you and the query. The pieces live in domain/sqldemo (ports + use cases), data/sqldemo (six adapters, from the sandbox's restricted role to the race track's barrier), and db/migration V10–V12; topic 18 picks up exactly here, keeping this topic's port shape while letting an ORM implement it.""")));
    }
}
