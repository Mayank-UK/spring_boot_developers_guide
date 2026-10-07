-- V12 — full-text search over posts (topic 17, demo db:search).
--
-- Two PostgreSQL-native choices made here, both worth reading twice:
--
-- 1) A GENERATED COLUMN, not a trigger-maintained column. The tsvector is
--    computed BY THE DATABASE from title/body on every INSERT and UPDATE —
--    STORED means it is physically saved, not recomputed per read — so it
--    can never drift from the row it indexes: no AFTER INSERT/UPDATE/DELETE
--    trigger trio to forget, no ordering hazards, no "the trigger was
--    disabled during the backfill" war stories. The trade: the expression
--    is frozen in DDL (changing weights or config = a new migration that
--    drops and regenerates the column) and it recomputes even when only
--    tags/status change. A trigger wins only when the maintenance logic
--    needs to be conditional or shared across tables; it isn't, so it
--    isn't here. (An expression GIN index over to_tsvector(...) directly is
--    the third option — no stored column, but then every ts_rank and
--    headline must recompute the expression. The column pays rent by
--    keeping the vector readable.)
--
--    Weighting: title hits weigh 'A', body hits weigh 'B' — a title match
--    outranks a body match for the same term, which is the one ranking
--    decision a search surface must make before it ships anything.
--
-- 2) A GIN index, not GiST. GIN (Generalized Inverted iNdex) maps each lexeme
--    to the rows containing it — exact, fast, larger, slower to write. GiST
--    is a lossy approximation: smaller and faster to update, but it can
--    return false positives the recheck must filter, and it cannot rank.
--    Posts are written rarely and searched often — the exact profile GIN
--    exists for. (GiST earns its keep on frequently-updated text or when
--    the index must serve distance operators beside text search.)
--
-- No seed data: the posts rows arrive at runtime through the existing
-- idempotent seed (SeedPosts + ON CONFLICT DO NOTHING); the generated column
-- computes their vectors as they land.
ALTER TABLE posts
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A')
        || setweight(to_tsvector('english', coalesce(body, '')), 'B')
    ) STORED;

CREATE INDEX posts_search_vector_idx ON posts USING GIN (search_vector);
