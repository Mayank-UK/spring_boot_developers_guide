package guide.domain.schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The key-strategy lab PORT: the minimum a uuid v4-vs-v7 comparison needs
 * from real tables. The lab tables (V3) are the experiment's apparatus, and
 * this interface states the apparatus's controls in domain language — load a
 * key population, read the primary-key index's size, walk rows in KEY order
 * (the b-tree's own order) with the heap's physical order alongside, and
 * clear the apparatus between runs.
 *
 * The insertion-order ground truth is ctid — the heap tuple identifier
 * (page, offset) PostgreSQL assigns physically — because no DEFAULT and no
 * application clock can fake it: rows inserted earlier get smaller ctids
 * within a fresh table, so comparing key order against heap order asks the
 * storage engine directly whether the key is time-ordered.
 */
public interface KeyStrategyTables {

    /** The lab table receiving random uuid v4 ids (V3__key_strategy.sql). */
    String V4_TABLE = "uuid_v4_demo";

    /** The lab table receiving time-ordered uuid v7 ids (V3__key_strategy.sql). */
    String V7_TABLE = "uuid_v7_demo";

    /**
     * Replaces every row of one lab table with the given ids (a fresh
     * experiment: truncate, then one batched insert).
     */
    void load(String table, List<UUID> ids);

    /** Bytes of the table's primary-key b-tree index (pg_relation_size). */
    long primaryKeyIndexBytes(String table);

    /**
     * Every row in KEY order (ORDER BY id — the b-tree's own sequence),
     * each carrying its heap position so callers can judge locality.
     */
    List<KeyedRow> rowsInKeyOrder(String table);

    /** Empties both lab tables — the experiment cleans up after itself. */
    void clear();

    /** One lab row: its key, the heap address (ctid, e.g. "(3,41)"), the heap
     * position as a single monotonic number (page major, offset minor —
     * insertion order's ground truth), and created_at for display. */
    record KeyedRow(UUID id, String ctid, long heapSequence, Instant createdAt) {
    }
}
