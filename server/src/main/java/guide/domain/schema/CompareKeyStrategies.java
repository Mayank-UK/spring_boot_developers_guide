package guide.domain.schema;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case: run the uuid key-strategy experiment. Two structurally identical
 * lab tables (V3), N random uuid v4 ids into one, N time-ordered uuid v7 ids
 * into the other, then measure what the database actually did: primary-key
 * index bytes per side (write-amplification residue — random keys split
 * b-tree pages before they are full) and key-order-vs-heap-order locality
 * (the structural proof: v7's key order IS insertion order, so its index
 * grows by appending; v4's is a shuffle).
 *
 * Discipline the demo is also teaching: the experiment CLEANUPS UP AFTER
 * ITSELF — the finally block truncates both tables no matter how the run
 * ended, so the demo is idempotent and leaves the shared database exactly as
 * it found it (minus whatever ANALYZE learned). And the bounds on rows are
 * this use case's own contract, exactly like ListPosts' page ceiling: an
 * unbounded ?rows= is a "fill my disk" invitation.
 */
public class CompareKeyStrategies {

    /** The ceiling on ?rows= — polite to the shared demo database. */
    public static final int MAX_ROWS = 50_000;

    /** How many leading ids each side reports for display. */
    static final int SAMPLE_IDS = 12;

    private final KeyStrategyTables tables;

    public CompareKeyStrategies(KeyStrategyTables tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    public Result<KeyStrategyReport> compare(int rows) {
        if (rows < 1 || rows > MAX_ROWS) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "rows must be between 1 and " + MAX_ROWS,
                    java.util.Map.of("fields",
                            java.util.Map.of("rows", "must be between 1 and " + MAX_ROWS))));
        }

        tables.clear();
        try {
            StrategyMeasurement v4 = runStrategy(KeyStrategyTables.V4_TABLE, "uuid v4 (random)", rows,
                    () -> UUID.randomUUID());
            StrategyMeasurement v7 = runStrategy(KeyStrategyTables.V7_TABLE, "uuid v7 (time-ordered)", rows,
                    UuidV7::newId);
            long delta = v4.primaryKeyIndexBytes() - v7.primaryKeyIndexBytes();
            return Result.success(new KeyStrategyReport(rows, v4, v7, delta,
                    verdict(rows, v4, v7, delta)));
        } finally {
            // Idempotent cleanup, on every path — the lab tables return to
            // empty whether the experiment succeeded, failed, or was interrupted.
            tables.clear();
        }
    }

    private StrategyMeasurement runStrategy(String table, String label, int rows,
            java.util.function.Supplier<UUID> mint) {
        List<UUID> ids = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            ids.add(mint.get());
        }
        long startedAt = System.nanoTime();
        tables.load(table, ids);
        long insertMs = (System.nanoTime() - startedAt) / 1_000_000;

        long indexBytes = tables.primaryKeyIndexBytes(table);
        List<KeyStrategyTables.KeyedRow> inKeyOrder = tables.rowsInKeyOrder(table);

        long compared = 0;
        long outOfOrder = 0;
        for (int i = 1; i < inKeyOrder.size(); i++) {
            compared++;
            if (inKeyOrder.get(i).heapSequence() < inKeyOrder.get(i - 1).heapSequence()) {
                outOfOrder++;
            }
        }
        List<String> sample = inKeyOrder.stream().limit(SAMPLE_IDS)
                .map(row -> row.id().toString())
                .toList();
        return new StrategyMeasurement(label, indexBytes, prettyBytes(indexBytes), insertMs,
                sample, compared, outOfOrder, outOfOrder == 0);
    }

    /** States only what was measured; the small-N case gets the honest sentence. */
    private static String verdict(int rows, StrategyMeasurement v4, StrategyMeasurement v7, long delta) {
        String locality = v7.keyOrderTracksInsertion()
                ? "v7's key order IS insertion order (0 out-of-order pairs)"
                : "v7's key order degenerated (" + v7.outOfOrderPairs() + " out-of-order pairs)";
        String scatter = v4.keyOrderTracksInsertion()
                ? "v4 happened to land sorted this time"
                : v4.outOfOrderPairs() + " of " + v4.comparedPairs() + " adjacent key pairs are out of insertion order";
        if (delta == 0) {
            return "At " + rows + " rows both primary-key indexes occupy the same page count ("
                    + v7.primaryKeyIndexSize() + " each) — the storage gap appears as row count grows; "
                    + "the structural difference is already visible: " + locality + ", while v4's keys scatter ("
                    + scatter + ").";
        }
        return "At " + rows + " rows the random v4 index is " + prettyBytes(delta) + " larger than the "
                + "time-ordered v7 index (" + v4.primaryKeyIndexSize() + " vs " + v7.primaryKeyIndexSize()
                + "): random keys split pages half-full, sequential keys append. Locality: " + locality
                + "; v4: " + scatter + ".";
    }

    /** The report's own byte formatter (8 KiB pages make kB the natural unit). */
    private static String prettyBytes(long bytes) {
        if (bytes >= 8192) {
            long kib = bytes / 1024;
            return kib + " KiB";
        }
        return bytes + " bytes";
    }
}
