package guide.domain.schema;

import java.util.List;

/**
 * What one key strategy actually did to the database, measured: the
 * primary-key index's size in bytes and pretty form (the storage cost — v4's
 * random inserts split pages before they are full, v7's sequential inserts
 * fill them), how long the batched insert took (informational — timing
 * depends on the machine, which is why the assertions in the test suite are
 * structural), the first ids in KEY order (so the demo can SHOW the
 * difference, not just claim it), and the locality verdict: walking rows in
 * key order, how many adjacent pairs came out of heap order. Zero
 * out-of-order pairs means the key order IS insertion order — a b-tree that
 * grows by appending.
 */
public record StrategyMeasurement(String strategy, long primaryKeyIndexBytes,
        String primaryKeyIndexSize, long insertDurationMs,
        List<String> idsInKeyOrder,
        long comparedPairs, long outOfOrderPairs,
        boolean keyOrderTracksInsertion) {

    public StrategyMeasurement {
        idsInKeyOrder = List.copyOf(idsInKeyOrder);
    }
}
