package guide.domain.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * UuidV7 unit contract, no database needed: version nibble is 7, variant
 * bits are the RFC spelling, a tight mint loop yields strictly increasing
 * ids (the same-millisecond counter doing its job — the property v4 lacks
 * and the b-tree story depends on), and a large batch stays unique.
 */
class UuidV7Test {

    @Test
    void idsCarryVersion7AndTheRfcVariant() {
        UUID id = UuidV7.newId();
        assertEquals(7, id.version(), "the version nibble must be 7");
        assertEquals(2, id.variant(), "the variant bits must be the RFC 4122 spelling (2)");
    }

    @Test
    void aTightMintLoopIsStrictlyMonotonic() {
        // 20_000 ids in far under a second: thousands share a millisecond, so
        // the counter (and only the counter) can keep the order honest.
        UUID previous = null;
        for (int i = 0; i < 20_000; i++) {
            UUID id = UuidV7.newId();
            if (previous != null) {
                assertTrue(id.compareTo(previous) > 0,
                        "consecutive ids must strictly increase, even within one millisecond");
            }
            previous = id;
        }
    }

    @Test
    void aLargeBatchIsUnique() {
        Set<UUID> seen = new HashSet<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            ids.add(UuidV7.newId());
        }
        seen.addAll(ids);
        assertEquals(ids.size(), seen.size(), "50_000 ids, zero collisions (74 random bits + counter)");
    }

    @Test
    void timestampsLeadTheBitsSoPrefixesClusterByTime() {
        // The structural claim the demo proves against the database, stated
        // in its cheapest form: ids from the same millisecond share their
        // first 12 hex digits (the timestamp) exactly. Bounded retries guard
        // the millisecond boundary: two mints that straddle a tick simply
        // share no prefix, so try adjacent pairs until one lands inside one
        // millisecond (they nearly always do — the mint loop is nanoseconds).
        boolean shared = false;
        for (int attempt = 0; attempt < 100 && !shared; attempt++) {
            UUID first = UuidV7.newId();
            UUID second = UuidV7.newId();
            assertTrue(second.compareTo(first) > 0, "later id sorts later");
            shared = first.toString().substring(0, 12).equals(second.toString().substring(0, 12));
        }
        assertTrue(shared, "some adjacent pair shares the timestamp prefix (same millisecond)");
    }
}
