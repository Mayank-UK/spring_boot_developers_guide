-- Topic 16 (sql-schema): the uuid key-strategy lab tables, SCHEMA ONLY —
-- rows live exactly as long as one db:uuid demo run (the demo truncates both
-- tables when it finishes; no seed, no leftovers).
--
-- TWO structurally identical tables — same uuid primary key, same created_at
-- — differing in nothing but WHICH uuid version the demo inserts. That is
-- the experiment's whole design: one variable. A single shared table would
-- pour both key populations into ONE b-tree and the comparison would measure
-- nothing; the schema itself encodes the control group.
--
--   id uuid PRIMARY KEY — the index under test IS the primary key's b-tree;
--                  no second column, so nothing but key strategy can explain
--                  a difference in index bytes or insert cost.
--   created_at timestamptz NOT NULL DEFAULT now() — the human-readable
--                  timestamp of each insert (the demo's rigorous insertion-
--                  order ground truth is the heap's ctid, which no DEFAULT
--                  can fake).
CREATE TABLE uuid_v4_demo (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uuid_v7_demo (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);
