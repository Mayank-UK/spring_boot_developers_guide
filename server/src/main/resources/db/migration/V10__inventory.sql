-- V10 — the oversell race's contested state (topic 17, demo db:race).
--
-- One row of sellable stock. The demo resets it, then fires N concurrent
-- reservations at it under three strategies (naive check-then-act, SELECT FOR
-- UPDATE guarded, atomic guarded UPDATE) and reports which ones oversold.
--
-- Column shapes are teaching surface, each deliberate:
--   sku      text UNIQUE — the demo's stable handle ('workshop-ticket'); no
--            client-supplied free text ever reaches the WHERE except as a
--            bind parameter (the injection demo owns that lesson).
--   stock    integer CHECK (stock >= 0) — the CHECK is deliberate and it is
--            NOT the fix for anything: it only catches ARITHMETIC that dips
--            below zero (an unguarded `stock = stock - 1` racing to -1
--            becomes a constraint error — a 500, not an oversell). The lost
--            update the naive strategy demonstrates writes a PLAUSIBLE value
--            (read - 1), which no CHECK can flag: two writers both read 1,
--            both write 0, and only `reserved` tells the truth. Constraints
--            are a backstop, never the concurrency control.
--   reserved integer NOT NULL DEFAULT 0 — the honest counter of successful
--            reservation claims. The demo's oversold verdict is reserved >
--            initial stock: a fact about HISTORY, which stock alone can no
--            longer show after a lost update.
--
-- No seed row on purpose: the race track INSERTs-or-resets its own row
-- before every run (ON CONFLICT (sku) DO UPDATE), so each run starts from a
-- state the response names — schema in migrations, run-scoped data in code.
CREATE TABLE inventory (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku      text NOT NULL UNIQUE,
    stock    integer NOT NULL CHECK (stock >= 0),
    reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0)
);
