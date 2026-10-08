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
 * Topic 16 — sql-schema. Group data, order 1. SQL and schema design,
 * PostgreSQL-specific where it matters, taught from THIS repository's real
 * schema: V1__posts.sql as the specimen, V2-V4 (this topic's own
 * migrations) for the audit pattern, the key-strategy lab, and COMMENT ON
 * annotations. Two live demos: db:schema-explorer (the database describing
 * itself through information_schema + pg_catalog) and db:uuid (the uuid
 * v4-vs-v7 experiment, proven with real inserts).
 */
public final class SqlSchemaTopic {

    private SqlSchemaTopic() {
    }

    public static Topic topic() {
        return new Topic("sql-schema", "SQL and schema design",
                "The database as a contract: relational thinking, PostgreSQL's type and "
                        + "constraint discipline, keys and why uuid v7 beats v4 for primary keys, "
                        + "normalization and its honest exceptions, indexes and what every one of "
                        + "them costs, migrations as append-only history, and the audit-ledger "
                        + "pattern — every claim checked against this repo's own schema.",
                TopicGroupId.DATA, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("relational-thinking", "Relational thinking",
                        "Sets and relations versus documents — when the relational model wins, when it honestly doesn't, and this repo's posts table as the specimen.",
                        text("rel:model", """
                                The relational model is one idea with consequences: data lives in RELATIONS — typed, unordered sets of rows identified by keys — and every question is answered by set operations (restrict, project, join) over those relations. That single idea buys three things a pile of documents cannot. A SCHEMA AS CONTRACT: a row that fails the contract is rejected at the door, by the database, no matter which code path wrote it. AD-HOC QUERYABILITY: the schema's shape, not a developer's foresight, decides what you can ask — the join you did not know you needed in month one is a SELECT in month twelve. And INTEGRITY ACROSS ENTITIES: a foreign key is a mathematical fact (this child's parent EXISTS) enforced on every write, forever. This guide commits to one relational dialect, PostgreSQL, as a project decision — no H2 compatibility mode, no lowest-common-denominator SQL: the interesting lessons (arrays, jsonb, timestamptz, partial indexes) ARE dialect."""),
                        text("rel:honesty", """
                                The honesty every relational chapter owes you: the model is not always the right tool. If your data is a bag of documents whose shape varies per item and evolves weekly — content drafts, event payloads, provider responses — a document store is genuinely simpler, and topic 19 (mongodb) builds that case properly. If the data is ephemeral, addressed by key, and worthless when it expires, that is a cache, and topic 20 (redis) is the answer; session blobs in PostgreSQL are a tax on every backup. But the moment two entities must agree — a post and its status, an order and its items, an audit row and the transaction that wrote it — the relational database is the cheapest correct tool you own, and "we'll add the constraints later" later never comes. The posts substrate made that bet; one table so far, but every rule the Java entity enforces has a shadow in the schema:"""),
                        code("rel:posts-ddl", "sql",
                                "server/src/main/resources/db/migration/V1__posts.sql — the guide's specimen table, every shape a documented decision",
                                "server/src/main/resources/db/migration/V1__posts.sql", """
                                CREATE TABLE posts (
                                    id         uuid PRIMARY KEY,
                                    title      varchar(120) NOT NULL
                                               CHECK (btrim(title) <> '' AND char_length(btrim(title)) <= 120),
                                    body       text NOT NULL,
                                    status     varchar(10) NOT NULL CHECK (status IN ('draft', 'published')),
                                    version    integer NOT NULL DEFAULT 0,
                                    tags       text[] NOT NULL DEFAULT '{}',
                                    created_at timestamptz NOT NULL,
                                    updated_at timestamptz NOT NULL,
                                    CHECK (updated_at >= created_at)
                                );

                                -- status filter: the listing's most common WHERE beside the pagination keys.
                                CREATE INDEX posts_status_idx ON posts (status);

                                -- The default listing order (createdAt DESC + id tie-break needs no index of
                                -- its own at demo scale; created_at DESC is the hot path).
                                CREATE INDEX posts_created_at_desc_idx ON posts (created_at DESC);
                                """),
                        text("rel:specimen", """
                                Read the specimen as a contract, because that is what it is. The PRIMARY KEY line promises identity; the CHECKs promise the same title and status rules the PostDraft factory enforces in Java — defense in depth, the database re-judging what the entity already judged, because a bulk import or a future service will not route through your factory. version defaults to 0 meaning never touched, and the table-level CHECK makes the clock's monotonicity a database fact, not a hopeful convention. Even the two indexes are decisions: they exist because status filtering and newest-first listing are the two WHERE shapes this API actually serves — an index nobody queries is pure write tax. The rest of this topic takes the specimen apart, one decision at a time.""")),
                section("types-and-constraints", "Types and constraints",
                        "PostgreSQL's type discipline — timestamptz, text versus varchar, numeric for money, arrays versus jsonb — and the CHECK/NOT NULL/UNIQUE/default rules that make the schema judge.",
                        text("types:time", """
                                PostgreSQL's type discipline starts with time. timestamptz (timestamp with time zone) over timestamp, always: a plain timestamp stores a wall-clock reading with NO zone, so its meaning silently depends on the server's timezone setting — the same column means different instants after a config change or a server move. timestamptz stores an instant (UTC internally) and renders it in whichever zone the session asks; java.time.Instant maps to it exactly, which is why every time column in this repo's migrations is timestamptz. Second discipline: text over varchar(n) unless the limit IS a domain rule. varchar(n) is not faster, not smaller, not more indexed — it is a CHECK constraint in costume. A 120-character title limit belongs in varchar(120) because 120 is a product decision (V1 mirrors PostDraft.TITLE_MAX); a 255 "default" on a description field is a fake rule wearing a type, and fake rules reject the 256th character of a legitimate sentence. body is therefore text: bounded in the domain (64 KiB of characters, a Java judgment), unbounded at the storage layer, because a byte cap in SQL would measure a DIFFERENT thing than the Java char count the API promises."""),
                        text("types:money", """
                                Money gets its own paragraph because it is where float quietly rots systems. float4/float8 are binary fractions: 0.10 has no exact representation, so SUM over a million rows drifts by cents. numeric(precision, scale) is exact decimal arithmetic — slower per operation, unnoticeable at application scale, and CORRECT. This repo's schema carries no money column; the rule stands anyway: money is numeric(12,2) (or integer minor units), never float. The third discipline is choosing between structured types: PostgreSQL gives you arrays and jsonb, and they answer different questions:"""),
                        code("types:posts-columns", "sql",
                                "V1__posts.sql — the column lines where type decisions concentrate",
                                "server/src/main/resources/db/migration/V1__posts.sql", """
                                status     varchar(10) NOT NULL CHECK (status IN ('draft', 'published')),
                                version    integer NOT NULL DEFAULT 0,
                                tags       text[] NOT NULL DEFAULT '{}',
                                created_at timestamptz NOT NULL,
                                """),
                        text("types:array-vs-jsonb", """
                                tags is text[] — a flat, unordered set of plain strings — and V1's header documents why not jsonb: exact membership asks the question the data actually answers (? = ANY(tags), zero marshalling beyond java.sql.Array), while jsonb earns its keep only if a tag were structured (counts, namespaces) or needed GIN-indexed containment over large sets. The general rule: ARRAYS for flat lists of one scalar type you filter by membership; JSONB for genuinely variable document shapes you store and retrieve whole (the audit ledger's payload, two sections down) — reached for reluctantly, because every field inside it is dynamically typed: the database will happily store {"amout": 42}, and no CHECK saves you at 3 a.m. Around the types sit the constraint disciplines: NOT NULL is the strongest cheap statement in SQL — decide it per column (audit_log.entity_id is nullable because a collection-level event has no row id). CHECK enforces what types cannot express (btrim(title) <> '' catches the whitespace-only title; updated_at >= created_at catches the lying clock). UNIQUE states a business fact; DEFAULT states birth semantics (version 0, tags '{}') so an INSERT that omits a column cannot create an ambiguous row. Together they are the schema's half of the contract the Java records hold.""")),
                section("primary-and-foreign-keys", "Primary and foreign keys",
                        "Natural versus surrogate identity, why this repo mints uuids in the application, what v7 buys over v4 — and foreign-key actions with their trade-offs.",
                        text("keys:natural-vs-surrogate", """
                                A primary key answers one question — WHICH ROW IS THIS — and the first decision is natural versus surrogate. A natural key is a real-world attribute (email, ISBN); it feels free and rots on contact: emails change and get recycled, and every change becomes an UPDATE cascading through every foreign key referencing it. A surrogate key means nothing outside the database and therefore never needs to change — identity is not data, it is an address. This repo mints SURROGATE UUIDS IN THE APPLICATION (PgPostRepository.create: UUID.randomUUID), not in the database, for two load-bearing reasons: the seed's stable ids survive storage swaps (the same twelve posts answer from the in-memory fake and from PostgreSQL — the port/adapter payoff), and the id exists before the INSERT does, so a create-and-reference flow needs no round trip. uuid v4 (fully random) versus v7 (time-ordered) is its own economics story — the demo two sections down proves it with real inserts. Short version: random keys scatter across the b-tree, time-ordered keys append, and the difference shows up in index size and write locality exactly when your table grows."""),
                        text("keys:foreign", """
                                Foreign keys are integrity the database enforces for free: posts will grow a comments table one day, and comments.post_id REFERENCES posts(id) makes an orphan comment IMPOSSIBLE — no application bug, no reconciliation job. The interesting decision is the ACTION, chosen per relationship because each encodes a different domain truth. ON DELETE CASCADE (delete the post, delete its comments) fits OWNERSHIP: the child is meaningless without the parent — but it is a loaded gun: one DELETE can cascade through millions of children inside one transaction. ON DELETE RESTRICT (refuse the parent's deletion until the children are gone) fits REFERENCE: you cannot delete a product while order lines still point at it — the business reassigns first, deliberately. ON DELETE SET NULL fits OPTIONAL association: delete the tag, and tagged posts keep existing, now untagged. Three verbs, three domain sentences. The honest note about THIS schema: posts is standalone (no FK yet), and audit_log is deliberately FK-free — a ledger must survive the deletion of the rows it remembers (an FK would forbid history about deleted entities, the opposite of an audit's job). Foreign keys are strong medicine; prescribe them per relationship, not as a reflex.""")),
                section("normalization-then-denormalization", "Normalization, then denormalization",
                        "1NF to 3NF in two pages, then the honest exceptions: precomputed counts and the tags array as a denormalized join.",
                        text("norm:normal-forms", """
                                Normalization is the process of making each table state ONE kind of fact, and the classic ladder compresses to three questions. FIRST NORMAL FORM: every column holds one atomic value per row — no comma-separated tag strings smuggled into a text column. (PostgreSQL arrays sit in a deliberate gray zone: a text[] column is a repeating group by Codd's definition, but it is TYPED, indexable, and filterable as a set — the discipline 1NF protects against is the untyped string blob, not the array.) SECOND: no partial dependency — a column must depend on the WHOLE key, which only bites composite keys (in a (post_id, tag) join table, storing the post's title there makes title depend on post_id alone). THIRD: no transitive dependency — non-key columns depend on the key, the whole key, and nothing but the key. The classic violation: storing the author's EMAIL on posts — email depends on the author, the author on the post, a chain — so an email change means updating every post they ever wrote; the 3NF shape is an authors table and a join, and the email changes in one row. Denormalization's price is always this: every copied fact is a second copy to find and fix on every change."""),
                        text("norm:denormalize", """
                                Then, honestly: 3NF is not a religion, it is the default you DEVIATE FROM ON PURPOSE, with a query in hand. The precomputed count is the classic: comment_count on posts violates 3NF (it depends on the comments, not the post) but turns "show the count on every card" from COUNT(*) per row into a column read — the write side pays so the hot read path does not. THE TAGS ARRAY IS THE SAME DECISION IN COSTUME: the fully normalized shape is posts, tags, and a post_tags join table; every post-with-tags read becomes a join. At guide scale that join is nothing; at read-heavy scale where the tag list ships WITH every post anyway, text[] collapses three tables into one column and one row read — bought with "cannot FK the tags" and "per-tag counting requires unnest" (a GIN index answers membership, not analytics). updated_at is a smaller denormalization: derivable from history, stored because "newest first" is the site's front door. The rule this codebase follows: normalize by default, denormalize when a MEASURED query hurts, store the reason in the migration's comments (V1's header is exactly that) — every denormalized fact is a maintenance contract your write path signed.""")),
                section("indexes", "Indexes",
                        "B-tree semantics, composite column order, partial and expression indexes, the cost of every index — and the catalog as the explorer's source. EXPLAIN is the arbiter.",
                        text("idx:btree", """
                                An index is a data structure you maintain on every write so some reads get faster — PostgreSQL's default is the B-tree, a sorted, balanced map from key to row location. What that buys: equality (=) is a descent; ranges (<, BETWEEN, LIKE 'spring%') are a descent plus a scan of adjacent entries; and results arrive IN KEY ORDER, so an index matching your ORDER BY skips the sort entirely (posts_created_at_desc_idx exists so the newest-first listing is an index walk, no sort node). What it does not buy: LIKE '%boot' (leading wildcard cannot descend), or filtering on a NON-LEADING column of a composite index (the b-tree sorts by the first column first — an index on (entity_type, entity_id, id) serves entity_type alone and entity_type+entity_id, but NOT entity_id alone; the leftmost-prefix rule decides composite order: equality columns first, range/sort column last — exactly how audit_log's forensic index was shaped). And the planner keeps its judgment: it may CHOOSE a seq scan for a small table — an index is a bet, and the house calls when it pays."""),
                        text("idx:partial-expression-cost", """
                                PostgreSQL adds two index shapes most schemas under-use. The PARTIAL index keys only matching rows: CREATE INDEX ... ON posts (created_at) WHERE status = 'draft' indexes the drafts alone — smaller, hotter, useless the moment the predicate differs. The EXPRESSION index keys a function's output: the posts API sorts by LOWER(title) COLLATE "C" (a deliberate fake/live parity decision in PgPostRepository), and at demo scale the sort is fine; the day it hurts, CREATE INDEX ... ON posts (LOWER(title) COLLATE "C") is the fix — the index stores the lowered keys, and ORDER BY LOWER(title) walks it without sorting. The rule for both: the index must match the QUERY'S shape, not the column's name. And the standing tax: every index is a second write on every INSERT and a liability on every UPDATE of a key column — which is why this schema carries exactly three, each annotated with the query it serves. Whether an index pays is never taste: EXPLAIN (ANALYZE) shows the plan and the real costs, and topic 17 makes it a working tool. Meanwhile, this topic's explorer never ships a hand-written schema description — it asks the database through the same catalogs psql's backslash commands read:"""),
                        code("idx:catalog-columns", "java",
                                "guide/data/schema/PgSchemaCatalog.java — the explorer's column reader: information_schema's standard view is replaced by pg_catalog where format_type and comments live",
                                "server/src/main/java/guide/data/schema/PgSchemaCatalog.java", """
                                private static final String COLUMNS_SQL = \"\"\"
                                        SELECT a.attname AS column_name,
                                               pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                                               NOT a.attnotnull AS nullable,
                                               pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) AS column_default,
                                               pg_catalog.col_description(a.attrelid, a.attnum) AS column_comment
                                        FROM pg_catalog.pg_attribute a
                                        LEFT JOIN pg_catalog.pg_attrdef ad
                                               ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
                                        WHERE a.attrelid = pg_catalog.to_regclass(?)
                                          AND a.attnum > 0 AND NOT a.attisdropped
                                        ORDER BY a.attnum
                                        \"\"\";
                                """),
                        demo("idx:explorer-demo", "db:schema-explorer",
                                "The schema explorer — the database describing itself",
                                "Pick any table this guide owns (posts, the audit ledger, the uuid lab tables, even flyway_schema_history): columns with PostgreSQL's own type spellings, constraints in pg_get_constraintdef's words, indexes with sizes, and the COMMENT ON annotations — all read live from information_schema and pg_catalog."),
                        text("idx:catalog-reading", """
                                Two catalog habits worth stealing from that adapter. information_schema is the SQL-standard view layer; pg_catalog is PostgreSQL's native one, and it owns everything the standard has no spelling for — format_type renders varchar(120) and text[] exactly as psql prints them, pg_get_constraintdef and pg_get_indexdef return definitions in the database's own words, pg_total_relation_size counts heap plus indexes plus TOAST. And note the parameter: to_regclass(?) takes the table name as a BIND PARAMETER and returns NULL for anything unknown — identifiers cannot be bound like values in general, so a validated name plus to_regclass is the honest pattern that keeps user input out of SQL text. The explorer endpoint wraps the same reads:"""),
                        code("idx:controller", "java",
                                "guide/presentation/demo/SqlSchemaDemoController.java — the explorer edge: thin, typed 404s for unknown tables, the area's mode shown honestly",
                                "server/src/main/java/guide/presentation/demo/SqlSchemaDemoController.java", """
                                @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
                                ResponseEntity<SchemaExplorerView> schema(@RequestParam(required = false) String table) {
                                    requireAvailable();
                                    return switch (exploreSchema.explore(table)) {
                                        case Success<List<TableInsight>> success -> ResponseEntity.ok(new SchemaExplorerView(
                                                SchemaCatalog.AREA, dataMode(), success.value()));
                                        case Failed<List<TableInsight>> failed -> throw AppException.of(failed.failure());
                                    };
                                }
                                """)),
                section("migrations-as-schema-history", "Migrations as schema history",
                        "Flyway's versioned and repeatable model, checksums, the forward-only discipline — and why this repo runs Flyway programmatically instead of at boot.",
                        text("mig:flyway-model", """
                                A migration tool turns your schema into an APPEND-ONLY HISTORY: each versioned script (V1__posts.sql, V2__audit_log.sql, ...) runs exactly once, in order, on every environment, and the fact that it ran is recorded — Flyway keeps a flyway_schema_history table with version, description, and a CHECKSUM of each applied script. The checksum is the discipline's teeth: edit a script that already ran and the next start fails validation, because the database no longer matches the history that produced it. Hence the one hard rule: NEVER EDIT AN APPLIED MIGRATION — fix forward only (production cannot un-apply V2; it can only apply V5). Alongside versioned scripts sit REPEATABLE ones (R__views.sql): re-derived artifacts — views, functions — re-applied whenever their checksum changes. This repo keeps migrations SCHEMA-ONLY, a family rule with a reason: data belongs to code (SeedPosts replays idempotently via ON CONFLICT DO NOTHING), because seed rows inside migrations replay stale data state on every fresh environment. Even documentation is a migration here — V4's COMMENT ON statements annotate tables and columns where psql, IDEs, and this topic's explorer can all read them:"""),
                        code("mig:annotations", "sql",
                                "server/src/main/resources/db/migration/V4__schema_annotations.sql — schema documentation as a migration, queryable from the catalog",
                                "server/src/main/resources/db/migration/V4__schema_annotations.sql", """
                                COMMENT ON TABLE posts IS 'The guide''s canonical resource: the API, GraphQL, and data topics all stand on it.';
                                COMMENT ON COLUMN posts.id IS 'Surrogate uuid minted in the application (never by the database) so identity survives storage swaps.';
                                COMMENT ON COLUMN posts.tags IS 'text[] not jsonb: a flat string set, filtered by ? = ANY(tags) — V1__posts.sql documents the full reasoning.';
                                """),
                        text("mig:programmatic", """
                                The deliberate surprise in this repo's application.yml: Flyway's autoconfiguration is OFF. Migrations run PROGRAMMATICALLY, through a SchemaMigrator seam the live-data activation calls lazily, because this application is built to boot and serve with PostgreSQL unreachable — degrade gracefully, the in-memory surfaces answer — and Boot's startup-run Flyway would make the database a boot-time dependency, crashing the process an outage should only bruise. The same seam keeps failures as VALUES with kinds: an unreachable PostgreSQL classifies as UNAVAILABLE (SQLSTATE class 08, the standard "connection exception" family — a 503 fact), while a broken script stays INTERNAL (our bug, 500) — two very different operational responses:"""),
                        code("mig:yaml", "yaml",
                                "server/src/main/resources/application.yml — the one property that moves Flyway from boot to the activation seam",
                                "server/src/main/resources/application.yml", """
                                  # Flyway OFF at startup — the deliberate Phase 6 decision. Migrations run
                                  # PROGRAMMATICALLY (guide.data.sql.SchemaMigrator) when the posts area first
                                  # switches to live data, because the app must boot and serve with PostgreSQL
                                  # unreachable (degrade gracefully): autoconfig-run Flyway at startup would
                                  # make PG a boot-time dependency. Topic 17 teaches the full reasoning.
                                  flyway:
                                    enabled: false
                                """),
                        code("mig:seam", "java",
                                "guide/data/sql/SchemaMigrator.java — the programmatic seam: migrate() as a Result, outages classified, never a crash",
                                "server/src/main/java/guide/data/sql/SchemaMigrator.java", """
                                public Result<MigrationSummary> migrate() {
                                    try {
                                        MigrateResult result = flyway().migrate();
                                        List<String> versions = result.migrations == null
                                                ? List.of()
                                                : result.migrations.stream()
                                                        .map(migration -> migration.version)
                                                        .filter(Objects::nonNull)
                                                        .toList();
                                        return Result.success(new MigrationSummary(result.migrationsExecuted, versions));
                                    } catch (RuntimeException failure) {
                                        return Result.failed(unavailableOrInternal(
                                                "migrations could not run against the configured database", failure));
                                    }
                                }
                                """),
                        text("mig:activation", """
                                This topic's demos reuse that seam through a small gate: LiveDbArea.ensureActivated() runs migrate() once, under a monitor, on the FIRST schema call — Flyway's schema history makes it idempotent, so re-calling costs one history query — and flips the ScenarioStore's 'db' area LIVE when it succeeds (the programmatic activation seam; the public PATCH /api/demo/scenario moves only the global mode). The once-guard stays DOWN on failure, so the first call after the dependency heals pays the activation again and succeeds. Every SQL-side bean is @Lazy for the same reason the posts substrate's are: contexts without a DataSource never construct them, and the full application pays for PostgreSQL exactly when a live call demands it.""")),
                section("key-strategy-uuidv7-vs-sequential", "Key strategy: uuid v7 versus random",
                        "Why random uuid v4 keys tax the b-tree, what time-ordered v7 buys, and where the bigserial counterpoint still wins — proven by real inserts.",
                        text("key:the-problem", """
                                A b-tree index is SORTED, and that is the whole story. Insert a monotonically increasing key and the tree APPENDS — the rightmost leaf fills, splits into a fresh rightmost page, every page stays packed; the hot page is one per insert, cache-friendly, and the index grows at its minimum rate. Insert a RANDOM key (uuid v4: 122 random bits) and the landing spot is anywhere in the tree: pages split in the MIDDLE while half-empty (random arrival splits pages near 70% full in practice), the index balloons relative to its sequential twin, and every insert dirties an arbitrary page the buffer pool may not have. Same rows, same key SIZE — different ORDER. uuid v7 (RFC 9562) fixes exactly this: 48 bits of Unix-millisecond timestamp in the high bits, a version nibble, a 12-bit per-millisecond counter, and 74 random bits for uniqueness — globally unique like v4, but SORTED by mint time. Against the classic bigserial: the sequence is smaller (8 bytes vs 16) and perfectly ordered, but it LEAKS (row 1000 came after row 999 — enumerable URLs, guessable volumes) and couples identity to one database. v7 is the compromise: unguessable enough, order-preserving, minted anywhere. Java still has no v7 constructor, so the guide carries the layout:"""),
                        code("key:uuidv7", "java",
                                "guide/domain/schema/UuidV7.java — the RFC 9562 layout: timestamp high, version 7, a per-millisecond counter so same-tick ids still sort monotonically",
                                "server/src/main/java/guide/domain/schema/UuidV7.java", """
                                public static synchronized UUID newId() {
                                    long millis = System.currentTimeMillis();
                                    long counter;
                                    if (millis > lastMillis) {
                                        counter = RANDOM.nextInt(0, 4096);
                                    } else {
                                        // … same tick, or the clock ran backwards: clamp and count
                                    }
                                    lastMillis = millis;
                                    lastCounter = counter;

                                    long msb = (millis & MILLIS_MASK) << 16        // 48 timestamp bits
                                            | (0x7L << 12)                          // version 7
                                            | (counter & COUNTER_MASK);             // monotonic counter
                                    long lsb = RANDOM.nextLong()
                                            & 0x3FFFFFFFFFFFFFFFL                   // 62 random bits
                                            | 0x8000000000000000L;                  // RFC variant (10xx)
                                    return new UUID(msb, lsb);
                                }
                                """),
                        text("key:the-experiment", """
                                The claim deserves proof, not vibes, so V3 creates the experiment's apparatus — two structurally identical tables differing in NOTHING but which uuid version gets inserted (one variable; a shared table would pool both key populations into one tree and measure mush).

                                The demo inserts N rows of each and reads back what the database actually did: the primary-key index's size in bytes (pg_relation_size — random splits should leave v4 fatter), and the STRUCTURAL proof — walk each table's rows in key order and compare against the heap's physical insertion order, which PostgreSQL reports as ctid (page, offset): a storage-engine fact no application clock can fake. v7 should show ZERO out-of-order pairs — its key order IS insertion order, so the index is an append log; v4 should scatter. The measurement is honest about small N too: at a few thousand rows both indexes may occupy the same handful of 8 KiB pages (the verdict says so — grow N and watch the gap open), which is why the test suite asserts STRUCTURE (locality, cleanup, bounds), never exact byte counts:"""),
                        code("key:v3-tables", "sql",
                                "server/src/main/resources/db/migration/V3__key_strategy.sql — the lab: two identical tables, one variable",
                                "server/src/main/resources/db/migration/V3__key_strategy.sql", """
                                CREATE TABLE uuid_v4_demo (
                                    id         uuid PRIMARY KEY,
                                    created_at timestamptz NOT NULL DEFAULT now()
                                );

                                CREATE TABLE uuid_v7_demo (
                                    id         uuid PRIMARY KEY,
                                    created_at timestamptz NOT NULL DEFAULT now()
                                );
                                """),
                        code("key:measure", "java",
                                "guide/domain/schema/CompareKeyStrategies.java — the locality measurement: key order versus the heap's insertion order, counted pairwise",
                                "server/src/main/java/guide/domain/schema/CompareKeyStrategies.java", """
                                long compared = 0;
                                long outOfOrder = 0;
                                for (int i = 1; i < inKeyOrder.size(); i++) {
                                    compared++;
                                    if (inKeyOrder.get(i).heapSequence() < inKeyOrder.get(i - 1).heapSequence()) {
                                        outOfOrder++;
                                    }
                                }
                                """),
                        code("key:ctid", "java",
                                "guide/data/schema/PgKeyStrategyTables.java — the ground-truth read: rows in key order, each carrying its physical heap address",
                                "server/src/main/java/guide/data/schema/PgKeyStrategyTables.java", """
                                return jdbc.query("SELECT id, ctid::text AS ctid, created_at FROM " + target + " ORDER BY id",
                                        (rs, rowNum) -> new KeyedRow(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("ctid"),
                                                heapSequence(rs.getString("ctid")),
                                                rs.getTimestamp("created_at").toInstant()));
                                """),
                        demo("key:uuid-demo", "db:uuid",
                                "uuid v4 versus v7 — the experiment, measured",
                                "Insert N rows into each lab table and watch the indexes answer: primary-key index bytes per side, insert duration, and the locality verdict (v7's key order tracks insertion order; v4's is a shuffle). The server truncates both tables when it finishes — the run is idempotent."),
                        text("key:client", """
                                The client renders the two strategies side by side — index size, insert time, the out-of-order pair counts, and the first ids in key order, where the difference is VISIBLE: v7's ids share their high halves (same milliseconds), v4's look like noise. One line of the fetch shows the demo's whole contract — a row count in, one measured report out:"""),
                        code("key:client-fetch", "ts",
                                "client/src/ui/demos/db/uuid_demo.tsx — the experiment console's guarded call",
                                "client/src/ui/demos/db/uuid_demo.tsx", """
                                const run = () => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () =>
                                    parseReport(await fetchJson(config, `/demo/db/uuid/compare?rows=${String(rows)}`)),
                                  ).then((result) => {
                                    setPanel(
                                      result.ok
                                        ? { kind: 'ready', report: result.value }
                                        : { kind: 'error', failure: result.failure },
                                    );
                                  });
                                };
                                """)),
                section("audit-logging-as-a-schema-pattern", "Audit logging as a schema pattern",
                        "The append-only change ledger: same-transaction writes, jsonb payloads versus narrow columns, and why the ledger refuses foreign keys.",
                        text("audit:pattern", """
                                An audit ledger answers questions the live tables cannot: who changed this row, when, and what did it look like afterwards — for forensics, compliance ("show me every access to this account"), and debugging when the question is WHICH WRITE made the data wrong. As a SCHEMA pattern it is one table, three rules. APPEND-ONLY: rows are inserted, never updated, never deleted — history that can be edited is not history. PostgreSQL cannot enforce that with table shape alone, so the discipline lives in the write path, optionally backed by a trigger or a REVOKE; state it in the schema's comments and hold it in code. SAME-TRANSACTION: the audit row commits in the SAME transaction as the change it records — an audit written after commit can be lost exactly when it mattered; one written before the change can describe a change that never happened. Topic 17's db:audit demo builds that write path; this topic owns the table it writes into:"""),
                        code("audit:v2", "sql",
                                "server/src/main/resources/db/migration/V2__audit_log.sql — the ledger: bigserial arrival order, loose entity reference, jsonb after-image",
                                "server/src/main/resources/db/migration/V2__audit_log.sql", """
                                CREATE TABLE audit_log (
                                    id          bigserial PRIMARY KEY,
                                    entity_type varchar(40) NOT NULL,
                                    entity_id   uuid,
                                    changed_at  timestamptz NOT NULL DEFAULT now(),
                                    actor       varchar(80) NOT NULL,
                                    payload     jsonb NOT NULL
                                );

                                -- The forensic lookup audits exist for: "everything about THIS row, newest
                                -- last". Composite column order is load-bearing — entity_type, entity_id are
                                -- the equality predicates, id rides along so the same index serves the ORDER
                                -- BY without a sort. The indexes section dissects exactly this prefix rule.
                                CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, id);
                                """),
                        text("audit:decisions", """
                                Three decisions in that shape repay the attention. ID IS BIGSERIAL — the one identity in this schema a sequence is RIGHT for: audit rows are born in one place, never addressed by callers, never merged across systems, and their only meaningful order IS arrival order; posts chose uuid for storage-swap survival, the ledger chooses a global gap-tolerant counter — same analysis, opposite answers. THE ENTITY REFERENCE IS DELIBERATELY LOOSE: no foreign key, because the ledger must survive the deletion of the rows it remembers and cannot reference every table it records — referential freedom, documented. PAYLOAD IS JSONB VERSUS NARROW COLUMNS, the honest trade: narrow typed columns (old_title, new_title) are constraint-friendly and read cheaply, but every schema change on the audited entity forks the ledger's schema too; jsonb absorbs any after-image at the cost of dynamic typing inside the payload and queries that reach INTO the document (payload->>'title' — GIN-indexable when needed, but reach you pay for). The ledger chose jsonb because audit payloads are write-once, read-rarely, and shape-shifting by nature; an access log queried by column every day might honestly choose narrow columns instead. Pattern, not dogma — same as every decision this topic has made.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic through curl: the explorer, one table's DDL summary, the uuid experiment — with the psql equivalents alongside.",
                        text("term:setup", """
                                The server carries everything this topic taught, live. The tour below speaks to a server on localhost:8080 (docker compose up, or your dev container); responses print raw and read fine. psql users get the same facts with backslash commands — \\d posts is the explorer per table, \\d+ adds V4's COMMENT ON annotations, \\di lists indexes — but the curl tour works from anywhere the API does. Watch the FIRST call on a fresh database: it runs the migrations lazily (V1 through V4) and then answers — the activation seam in motion — while every later call is a plain catalog read."""),
                        code("term:tables", "bash",
                                "every table this guide owns, then one table in full detail", null, """
                                # every user table (including flyway_schema_history — it is ours too)
                                curl -s localhost:8080/api/demo/db/schema \\
                                  | python3 -c 'import json,sys; print([t["name"] for t in json.load(sys.stdin)["tables"]])'
                                # → ["flyway_schema_history","posts","comments", …]

                                # the specimen in full: columns, constraints, indexes, sizes, annotations
                                curl -s 'localhost:8080/api/demo/db/schema?table=posts'

                                # an unknown table answers the typed 404, naming what IS known
                                curl -s 'localhost:8080/api/demo/db/schema?table=dropout'
                                # → {"error":{"code":"not_found", … "details" naming the known tables}}
                                """),
                        code("term:ddl-uuid", "bash",
                                "the DDL summary, then the key-strategy experiment", null, """
                                # catalog-derived DDL (labeled honestly: PostgreSQL does not store the original script)
                                curl -s localhost:8080/api/demo/db/schema/ddl/audit_log \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["ddl"])'

                                # the experiment: 5000 rows per side, index bytes + locality verdict
                                curl -s -X POST 'localhost:8080/api/demo/db/uuid/compare?rows=5000'

                                # grow it and watch the index-size gap open (tables truncate between runs)
                                curl -s -X POST 'localhost:8080/api/demo/db/uuid/compare?rows=50000'
                                # → compare uuidV4.primaryKeyIndexSize against uuidV7.primaryKeyIndexSize

                                # out-of-bounds row counts answer the validation envelope, not a stack trace
                                curl -s -X POST 'localhost:8080/api/demo/db/uuid/compare?rows=0'
                                # → {"error":{"code":"validation_failed", …}}
                                """),
                        text("term:what-to-notice", """
                                Three things to notice on the tour. The explorer's ?table= miss is a TYPED 404 whose details name the known tables — defensive input handling shaped like an API. The uuid report's verdict states only what was measured, including the small-N case where both indexes fit the same page count — the honesty the test suite encodes by asserting structure, never exact sizes. And after the experiment the lab tables are empty again (the finally block truncated them): check uuid_v7_demo in the explorer — the demo cleaned up after itself, as a good tenant of a shared database should.""")));
    }
}
