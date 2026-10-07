-- Phase 6 (data foundation): the posts table, SCHEMA ONLY.
--
-- Deliberate shapes, each mirroring a domain rule from guide.domain.posts so
-- the database re-enforces what the entities already judge (defense in depth):
--   id         uuid     — the seed's stable UUIDs (SeedPosts) survive storage
--                         swaps; uuid (not bigserial) because identity is
--                         minted in the application, never by the sequence.
--   title      varchar(120) + CHECK — PostDraft.TITLE_MAX; btrim mirrors the
--                         Java "after trimming" rule (a whitespace-only title
--                         is absent), char_length counts characters like Java.
--   body       text     — bounded in the domain (64 KiB chars), unbounded here:
--                         text's limit is 1 GiB and a DB-level byte cap would
--                         measure a DIFFERENT thing than the Java char count.
--   status     varchar(10) + CHECK ('draft'/'published') — the wire spellings,
--                         not the enum's Java names: the DB stores what the
--                         API says, and the adapter maps enum <-> wire word.
--   version    integer  — the optimistic-concurrency token; default 0 means
--                         "never touched" — rows the ADAPTER writes always
--                         state their version explicitly (1 at birth, +1 per
--                         revision, exactly like Post.initial/revised).
--   tags       text[]   — see the choice note below the table.
--   created_at / updated_at timestamptz — instants with zone, the honest type
--                         for Instants (timestamp without time zone would
--                         silently depend on the server's timezone setting).
--
-- NO seed data here, on purpose: migrations own SCHEMA, SeedPosts.java owns
-- SEED — the runtime idempotent re-seed (INSERT ... ON CONFLICT DO NOTHING)
-- replays the same twelve ids into any fresh database, which is exactly the
-- split topic 16 teaches (schema in migrations, data in code).
--
-- Why text[] and not jsonb for tags: tags are a flat, unordered set of plain
-- strings — PG arrays give exact membership (? = ANY(tags)) with zero
-- marshalling beyond java.sql.Array, no join table, no key/value ceremony.
-- jsonb would earn its keep only if a tag were ever structured (counts,
-- namespaces, nested values) or needed GIN-indexed containment over LARGE
-- sets; for a demo-scale exact-match filter that is machinery without a
-- lesson. Revisit = a one-line migration (ALTER TABLE ... TYPE jsonb) plus a
-- row-mapper change — the adapter isolates the choice either way.
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
