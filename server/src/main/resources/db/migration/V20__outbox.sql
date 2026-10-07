-- V20 — the transactional outbox (topic 22, demos msg:stream + msg:outbox).
--
-- THE PROBLEM THIS TABLE IS: a business mutation and the event it should
-- cause are TWO writes aimed at TWO systems (the database and the broker).
-- There is no transaction that spans both — PostgreSQL commits while the
-- broker publish can fail, and a broker publish can succeed while the
-- database rolls back. Whichever order you pick, a crash in between leaves
-- one side done and the other not: the post updated but the event lost, or
-- the event live but the post never changed. That is the DUAL-WRITE problem,
-- and "just publish inside the transaction" cannot fix it because the
-- transaction cannot reach the broker.
--
-- THE OUTBOX ANSWER: don't write to the broker inside the business
-- transaction at all. Write the EVENT to a table in the SAME database and
-- the SAME transaction as the mutation — one COMMIT covers both rows, so
-- they exist together or not at all. A separate RELAY process later reads
-- unprocessed rows and publishes them to the stream, marking each processed
-- only after the broker accepted it. Crash anywhere: the row's existence is
-- exactly as durable as the business change it describes, and a row that
-- was claimed but never confirmed simply gets claimed again.
--
-- Column decisions worth reading twice:
--
--   id bigserial — arrival order IS the publish order for the relay's
--       batches; identity minted by the sequence (unlike posts' uuid,
--       where identity is domain state — an outbox row is plumbing, and
--       plumbing may ask the database for its own numbers).
--
--   aggregate_type/aggregate_id/event_type — the event's routing identity,
--       kept as plain text columns (not inside payload) so the relay can
--       filter, inspect, and dead-letter WITHOUT parsing JSON. aggregate_*
--       name the SOURCE ("post", its id); event_type names what HAPPENED
--       ("post.published").
--
--   payload/headers jsonb — the event body and its envelope metadata
--       (trace ids, correlation ids, schema version). jsonb because the
--       database never queries INTO the event: it stores and replays blobs.
--       The business table stays relational; the event travels as a
--       document. Two storage shapes, one transaction, each doing what it
--       is good at.
--
--   created_at timestamptz default now() — queue-since timestamp; the
--       relay's batches read oldest-first off it.
--
--   processed_at timestamptz NULL — NULL means "not yet handed to the
--       broker" (the only rows the relay claims); NOT NULL carries the
--       completion instant. One nullable timestamp IS the whole work-queue
--       state machine's visible half; attempts below is the other.
--
--   attempts int default 0 — how many relay claims this row has survived
--       without completion. The relay increments it on every claim; a row
--       still unprocessed after MORE than the threshold claims is a poison
--       event and gets dead-lettered (parked via processed_at + shipped to
--       the DLQ stream). attempts and processed_at together encode three
--       states: (NULL) pending · (NOT NULL, attempts <= max) delivered ·
--       (NOT NULL, attempts > max) dead-lettered. No status column needed
--       — the state machine fits in two columns, and every production
--       outbox table you will meet is some elaboration of exactly these.
--
-- The PARTIAL INDEX below is the relay's whole read path: only unprocessed
-- rows, in created_at order. A partial (filtered) index stays small no
-- matter how large the processed history grows — the dead weight of
-- yesterday's events never slows today's claim — and it documents intent in
-- DDL: this table's hot query is "the next unprocessed rows".
--
-- Retention note (not implemented, deliberately): a production outbox grows
-- forever unless something deletes processed rows (or partitions them away)
-- on a cadence. The demo keeps everything so /state can show history; the
-- teaching point is that processed rows are ARCHIVE, not queue.
CREATE TABLE outbox (
    id             bigserial PRIMARY KEY,
    aggregate_type text NOT NULL,
    aggregate_id   text NOT NULL,
    event_type     text NOT NULL,
    payload        jsonb NOT NULL,
    headers        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    processed_at   timestamptz NULL,
    attempts       int NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

-- The relay's claim path: unprocessed rows in arrival order. Partial
-- (WHERE processed_at IS NULL) so processed history never pays into it.
CREATE INDEX outbox_unprocessed_idx ON outbox (created_at) WHERE processed_at IS NULL;
