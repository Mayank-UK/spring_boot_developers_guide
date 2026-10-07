-- V11 — the SQL-by-hand lab's dataset (topic 17, demo db:sql-lab).
--
-- A small org chart, three levels deep (CEO → leads → reports), one
-- self-referencing manager FK — the shape every teaching query loves:
--   recursive CTE  — walk id → manager_id down the tree (db/sql/lab?query=cte)
--   window funcs   — RANK/ROW_NUMBER/SUM OVER per department (query=window)
--   LAG            — days between each department's consecutive hires (query=lag)
--   join+aggregate — one rollup row per department (query=aggregate)
--
-- Unlike every other migration in this repo, THIS ONE CARRIES SEED DATA —
-- deliberately. The lab dataset is a fixed TABLE the way V10's inventory
-- table is a fixed TABLE: the queries the lab teaches assert exact, named
-- rows (Liskov's platform rank, Knuth's 140-day gap), so the data is part of
-- the contract, deterministic and replayable into every fresh database.
-- Schema-only tables (posts, inventory) seed from code because live writes
-- must survive re-seeding; a lab dataset nobody mutates loses nothing by
-- living in the migration that defines it.
--
-- Types follow the repo's data-modeling rules: money as INTEGER cents
-- (never floats — the rounding rule stated in DDL), hired_on as a bare
-- date (a hire day has no time-of-day and no zone to be wrong about),
-- ids as stable text handles (they are NAMES in queries, not identity
-- minted by the application the way posts' uuids are).
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
    ('staff-004', 'Radia Perlman',     'staff-001', 16200000, 'product',   '2019-02-18'),
    ('staff-005', 'Linus Torvalds',    'staff-002', 12400000, 'platform',  '2019-05-06'),
    ('staff-006', 'Barbara Liskov',    'staff-002', 13100000, 'platform',  '2020-01-20'),
    ('staff-007', 'Margaret Hamilton', 'staff-002', 11900000, 'platform',  '2020-09-14'),
    ('staff-008', 'Claude Shannon',    'staff-003', 11200000, 'data',      '2019-11-04'),
    ('staff-009', 'E. F. Codd',        'staff-003', 12000000, 'data',      '2021-03-08'),
    ('staff-010', 'Alan Kay',          'staff-004', 12800000, 'product',   '2020-06-01'),
    ('staff-011', 'Adele Goldberg',    'staff-004', 11600000, 'product',   '2021-08-23'),
    ('staff-012', 'Donald Knuth',      'staff-004', 13500000, 'product',   '2022-01-10');

-- The department filter is every lab query's PARTITION KEY; at 12 rows the
-- planner will (correctly) ignore this index — the reading-query-plans
-- section uses exactly that as its "the planner is right at small scale"
-- exhibit, and the index earns its keep the day the lab table grows.
CREATE INDEX lab_staff_department_idx ON lab_staff (department);
