-- Topic 16 (sql-schema): the append-only audit ledger, SCHEMA ONLY — no seed
-- rows (the family rule: migrations own structure, code owns data; topic 17's
-- db:audit demo and this topic's audit section are the writers).
--
-- Shape decisions, each one a teachable rule:
--   id bigserial — the ONE identity in this schema a sequence is RIGHT for:
--                  audit rows are born in exactly one place, never addressed
--                  by callers, never merged across systems, and their only
--                  meaningful order IS arrival order — a global, gap-tolerant
--                  monotonic counter is precisely the semantic wanted. (posts
--                  chose uuid because its identity is minted in the app and
--                  must survive storage swaps; audit_log is the honest
--                  counterexample, and the key-strategy section walks the
--                  whole trade.)
--   entity_type / entity_id — WHAT changed, spelled loosely ON PURPOSE: a
--                  polymorphic ledger records many tables' rows, so it cannot
--                  foreign-key to all of them — and an FK would forbid
--                  history about DELETED rows, the opposite of an audit's
--                  job. Deliberate referential freedom, documented here.
--   changed_at timestamptz NOT NULL DEFAULT now() — timestamptz, always (a
--                  timestamp without zone silently depends on the server's
--                  timezone setting); the DB clock as DEFAULT is the
--                  tiebreaker for writers that forget to state a time.
--   actor varchar(80) NOT NULL — WHO did it (user id, "system", a job name).
--                  An audit row without an author is a rumor, so NOT NULL.
--   payload jsonb NOT NULL — the after-image (or delta) of the change.
--                  jsonb (binary, parsed at write time) not json (text,
--                  reparsed per read): an audit payload is written once and
--                  read rarely, but when it IS read the jsonb operators and
--                  a possible GIN index must work. Flexible BY DESIGN — every
--                  entity version may carry different fields; the trade-off
--                  versus narrow typed columns is the audit section's point.
--
-- Append-only is NOT a shape the table can enforce by itself in PostgreSQL
-- (there is no "no UPDATE/DELETE" constraint) — it is a write-path contract
-- (topic 17's demo writes audit rows in the SAME transaction as the change)
-- plus, where it matters for real, a trigger that raises or a REVOKE.
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
