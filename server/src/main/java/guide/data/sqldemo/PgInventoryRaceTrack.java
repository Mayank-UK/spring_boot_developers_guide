package guide.data.sqldemo;

import guide.domain.sqldemo.InventoryRaceTrack;
import guide.domain.sqldemo.RaceOutcome;
import guide.domain.sqldemo.RaceStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The oversell race's PostgreSQL adapter — one contested inventory row, N
 * REAL threads over REAL pooled connections, three ways to write "reserve
 * one", all under READ COMMITTED (PostgreSQL's default, and the level the
 * demo's verdicts are stated for).
 *
 * THE RENDEZVOUS (naive only): a CyclicBarrier between the SELECT and the
 * UPDATE, INSIDE the transaction. Every naive implementation has this gap —
 * it is where the business logic lives (a price check, an external call,
 * anything) — and the barrier merely WIDENS it so the race is the lesson,
 * not a lottery. Without it the same bug still happens; you just have to
 * run it again. Threads at the barrier hold their connections, which is
 * exactly why the use case caps concurrency at HikariCP's maximum-pool-size.
 *
 * WHY THE WRITE-BACK LOSES: the naive path binds the COMPUTED value
 * (read − 1) into {@code SET stock = ?}. When two writers race, the second
 * one's UPDATE waits for the row lock, then applies its SET against the
 * CURRENT row (READ COMMITTED re-evaluates the target row): stock is
 * blindly overwritten with the same plausible number while reserved —
 * RELATIVE arithmetic — honestly compounds. Final state: stock says
 * "one left was taken", reserved says "N were taken". A CHECK (stock >= 0)
 * cannot catch this; history can. That is the whole demo.
 */
public final class PgInventoryRaceTrack implements InventoryRaceTrack {

    /** The demo's contested row — fixed so responses can name it. */
    static final String SKU = "workshop-ticket";

    private static final String RESET_SQL = """
            INSERT INTO inventory (sku, stock) VALUES (?, ?)
            ON CONFLICT (sku) DO UPDATE SET stock = EXCLUDED.stock, reserved = 0""";

    private static final String SELECT_STOCK_SQL = "SELECT stock FROM inventory WHERE sku = ?";

    private static final String SELECT_FOR_UPDATE_SQL = "SELECT stock FROM inventory WHERE sku = ? FOR UPDATE";

    /** naive: the computed value bound as a PARAMETER — the lost-update write. */
    private static final String NAIVE_WRITE_BACK_SQL =
            "UPDATE inventory SET stock = ?, reserved = reserved + 1 WHERE sku = ?";

    /** guarded: relative decrement, safe BECAUSE the row lock already serializes the decision. */
    private static final String GUARDED_DECREMENT_SQL =
            "UPDATE inventory SET stock = stock - 1, reserved = reserved + 1 WHERE sku = ?";

    /** atomic: guard and decrement indivisible — rows-affected is the verdict. */
    private static final String ATOMIC_GUARDED_SQL =
            "UPDATE inventory SET stock = stock - 1, reserved = reserved + 1 WHERE sku = ? AND stock >= 1";

    private static final String SELECT_FINAL_SQL = "SELECT stock, reserved FROM inventory WHERE sku = ?";

    private static final long ATTEMPT_TIMEOUT_SECONDS = 30;

    private enum Verdict {
        RESERVED, DECLINED
    }

    private final DbDemoActivation activation;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PgInventoryRaceTrack(DbDemoActivation activation, JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.activation = Objects.requireNonNull(activation, "activation");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public RaceOutcome run(RaceStrategy strategy, int concurrency, int initialStock) {
        activation.ensureReady();
        jdbc.update(RESET_SQL, SKU, initialStock);

        CountDownLatch startGun = new CountDownLatch(1);
        CyclicBarrier rendezvous = new CyclicBarrier(concurrency);
        AtomicInteger errors = new AtomicInteger();
        List<Verdict> verdicts = runRacers(strategy, concurrency, startGun, rendezvous, errors);

        int succeeded = count(verdicts, Verdict.RESERVED);
        int declined = count(verdicts, Verdict.DECLINED);
        Integer finalStock = jdbc.queryForObject(SELECT_FINAL_SQL,
                (rs, rowNum) -> rs.getInt("stock"), SKU);
        Integer finalReserved = jdbc.queryForObject(SELECT_FINAL_SQL,
                (rs, rowNum) -> rs.getInt("reserved"), SKU);
        boolean oversold = finalReserved != null && finalReserved > initialStock;
        return new RaceOutcome(strategy.wireName(), concurrency, initialStock, concurrency,
                succeeded, declined, oversold,
                finalStock == null ? -1 : finalStock,
                finalReserved == null ? -1 : finalReserved,
                story(strategy, concurrency, initialStock, succeeded, oversold,
                        finalStock == null ? -1 : finalStock, errors.get()));
    }

    // ---- the three strategies, each one thread's attempt ----

    private List<Verdict> runRacers(RaceStrategy strategy, int concurrency,
            CountDownLatch startGun, CyclicBarrier rendezvous, AtomicInteger errors) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<Verdict>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return switch (strategy) {
                        case NAIVE -> naiveAttempt(rendezvous);
                        case GUARDED -> guardedAttempt();
                        case ATOMIC -> atomicAttempt();
                    };
                }));
            }
            startGun.countDown();
            List<Verdict> verdicts = new ArrayList<>();
            for (Future<Verdict> future : futures) {
                try {
                    verdicts.add(future.get(ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (Exception broken) {
                    errors.incrementAndGet();
                    verdicts.add(Verdict.DECLINED);
                }
            }
            return verdicts;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Check-then-act across a (widened) gap, in a transaction — the bug itself. */
    private Verdict naiveAttempt(CyclicBarrier rendezvous) {
        return tx.execute(status -> {
            Integer stock = jdbc.queryForObject(SELECT_STOCK_SQL, Integer.class, SKU);
            if (stock == null || stock < 1) {
                return Verdict.DECLINED;
            }
            try {
                // The gap every naive implementation has, widened so the race
                // is deterministic: all racers read BEFORE any racer writes.
                rendezvous.await(15, TimeUnit.SECONDS);
            } catch (Exception neverRacedCleanly) {
                return Verdict.DECLINED;
            }
            jdbc.update(NAIVE_WRITE_BACK_SQL, stock - 1, SKU);
            return Verdict.RESERVED;
        });
    }

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
    }

    /** ONE statement: the WHERE guard and the decrement are indivisible. */
    private Verdict atomicAttempt() {
        return jdbc.update(ATOMIC_GUARDED_SQL, SKU) > 0 ? Verdict.RESERVED : Verdict.DECLINED;
    }

    private static int count(List<Verdict> verdicts, Verdict wanted) {
        return (int) verdicts.stream().filter(wanted::equals).count();
    }

    private static String story(RaceStrategy strategy, int concurrency, int initialStock,
            int succeeded, boolean oversold, int finalStock, int errors) {
        String base = switch (strategy) {
            case NAIVE -> "all " + concurrency + " racers read stock=" + initialStock
                    + ", decided in Java, then wrote their computed value back over each other — "
                    + succeeded + " reservations claimed, stock still reads " + finalStock
                    + ". Only reserved tells the truth: the shelf lies because every writer "
                    + "overwrote a number it no longer had the right to say.";
            case GUARDED -> "SELECT FOR UPDATE serialized the racers: each read-decide-write held "
                    + "the row lock, so every decision was made against post-commit truth — "
                    + "exactly " + Math.min(succeeded, initialStock) + " claim(s) succeeded and the "
                    + "rest declined cleanly. Correct, at the price of lock-shaped waiting.";
            case ATOMIC -> "one statement per racer: the stock >= 1 guard and the decrement are "
                    + "indivisible, rows-affected is the verdict — exactly "
                    + Math.min(succeeded, initialStock) + " claim(s) succeeded, no locks held "
                    + "between statements, no lottery. This is the shape to reach for first.";
        };
        return errors == 0 ? base : base + " (" + errors + " attempt(s) errored — rerun for a clean race)";
    }
}
