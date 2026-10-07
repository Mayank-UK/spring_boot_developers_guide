-- Topic 16 (sql-schema): schema DOCUMENTATION that lives in the database.
--
-- COMMENT ON is the one migration-shaped place a schema can carry its own
-- manual: comments ride along with pg_dump, surface in psql's \d+ and in
-- every serious IDE, and — the reason this demo's tables care — are
-- QUERYABLE from the catalog (obj_description for tables, col_description
-- for columns), which is exactly how the db:schema-explorer demo renders
-- them. No structure changes in this file: annotations only, which is also
-- why they land as their own versioned migration — schema shape (V1-V3) and
-- schema documentation (this file) evolve on separate timelines.
COMMENT ON TABLE posts IS 'The guide''s canonical resource: the API, GraphQL, and data topics all stand on it.';
COMMENT ON COLUMN posts.id IS 'Surrogate uuid minted in the application (never by the database) so identity survives storage swaps.';
COMMENT ON COLUMN posts.version IS 'Optimistic-concurrency token: every UPDATE carries WHERE version = ? (PgPostRepository).';
COMMENT ON COLUMN posts.tags IS 'text[] not jsonb: a flat string set, filtered by ? = ANY(tags) — V1__posts.sql documents the full reasoning.';
COMMENT ON TABLE audit_log IS 'Append-only change ledger: written in the same transaction as the change, queried by (entity_type, entity_id).';
COMMENT ON COLUMN audit_log.payload IS 'jsonb after-image: flexible by design, one shape per entity version — the trade-off versus narrow columns is taught in topic 16.';
COMMENT ON TABLE uuid_v4_demo IS 'Key-strategy lab: random uuid v4 ids, inserted (then truncated) by the db:uuid demo.';
COMMENT ON TABLE uuid_v7_demo IS 'Key-strategy lab: time-ordered uuid v7 ids, inserted (then truncated) by the db:uuid demo.';
