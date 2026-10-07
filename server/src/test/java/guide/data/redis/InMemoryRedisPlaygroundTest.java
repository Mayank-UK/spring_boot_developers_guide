package guide.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.redis.RecentMessages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The in-memory fake's contract, asserted op by op — because the fake is not
 * a stub but a stand-in: everything the live test proves against redis:7
 * must hold identically here, or the port's fake/live parity is theater.
 * The contract bits a fake usually gets wrong get their own tests: negative
 * range indices, sorted-set (score, member) ordering, sorted SMEMBERS, the
 * TTL rounding direction, LAZY expiry (absent on next access, memory freed
 * only when read), WRONGTYPE parity, and the namespace applied at the one
 * door every op walks through.
 */
class InMemoryRedisPlaygroundTest {

    private final RecentMessages recent = new RecentMessages();
    private final InMemoryRedisPlayground playground = new InMemoryRedisPlayground(recent);

    @Test
    void namespaceIsAppliedToEveryKeyTheFakeStores() {
        playground.set("league", "seeded");
        playground.hashSet("profile", "name", "Ada");
        playground.setAdd("tags", "spring");
        assertEquals("guide:demo:league", playground.namespaced("league"));
        assertTrue(playground.keys().containsAll(
                java.util.Set.of("guide:demo:league", "guide:demo:profile", "guide:demo:tags")),
                "every stored key carries the adapter prefix: " + playground.keys());
        assertTrue(playground.keys().stream().allMatch(k -> k.startsWith("guide:demo:")),
                "no bare key ever leaks into the store");
    }

    @Test
    void stringOpsRoundTripWithRedisSemantics() {
        assertEquals("OK", playground.set("k", "v1"));
        assertEquals(Optional.of("v1"), playground.get("k"));
        assertTrue(playground.exists("k"));
        assertTrue(playground.delete("k"));
        assertFalse(playground.delete("k"), "deleting an absent key answers false, not an error");
        assertEquals(Optional.empty(), playground.get("k"));
        assertFalse(playground.exists("k"));
    }

    @Test
    void hashOpsStoreFieldsWithoutWholeObjectRoundTrips() {
        playground.hashSet("profile", "name", "Ada");
        playground.hashSet("profile", "lang", "java");
        assertEquals(Map.of("name", "Ada", "lang", "java"), playground.hashGetAll("profile"));
        assertEquals(Map.of(), playground.hashGetAll("absent"), "HGETALL of nothing is empty, not an error");
    }

    @Test
    void listOpsPrependAndHonorNegativeRangeIndices() {
        assertEquals(1, playground.listPush("feed", "first"));
        assertEquals(2, playground.listPush("feed", "second"));
        assertEquals(3, playground.listPush("feed", "third"));
        assertEquals(List.of("third", "second", "first"), playground.listRange("feed", 0, -1),
                "LPUSH prepends: 0..-1 reads newest first");
        assertEquals(List.of("third", "second"), playground.listRange("feed", 0, 1));
        assertEquals(List.of("second", "first"), playground.listRange("feed", -2, -1),
                "negative indices count from the tail");
        assertEquals(List.of(), playground.listRange("absent", 0, -1));
    }

    @Test
    void setOpsAreIdempotentAndMembersAreSorted() {
        assertEquals(1, playground.setAdd("tags", "spring"));
        assertEquals(0, playground.setAdd("tags", "spring"), "SADD of an existing member adds nothing");
        assertEquals(1, playground.setAdd("tags", "aop"));
        assertEquals(1, playground.setAdd("tags", "jdbc"));
        assertEquals(List.of("aop", "jdbc", "spring"),
                List.copyOf(playground.setMembers("tags")),
                "members are sorted for the deterministic wire order the live adapter also serves");
    }

    @Test
    void sortedSetOrdersByScoreThenMemberAndReportsUpserts() {
        assertEquals(1, playground.zAdd("league", 10, "ada"));
        assertEquals(1, playground.zAdd("league", 12, "grace"));
        assertEquals(1, playground.zAdd("league", 9, "knuth"));
        assertEquals(0, playground.zAdd("league", 11, "ada"), "ZADD of an existing member updates, adds nothing");
        List<guide.domain.redis.RedisPlayground.ScoredMember> board = playground.zRange("league", 0, -1);
        assertEquals(3, board.size());
        assertEquals("knuth", board.get(0).member());
        assertEquals("ada", board.get(1).member(), "updated score moves the member");
        assertEquals(11.0, board.get(1).score());
        assertEquals("grace", board.get(2).member());
        assertEquals(2, playground.zRange("league", 0, 1).size(), "range slices respect bounds");
        assertEquals(1, playground.zAdd("league", 9, "aaron"));
        assertEquals("aaron", playground.zRange("league", 0, 0).getFirst().member(),
                "score ties break by member order");
    }

    @Test
    void ttlWritesAreAtomicAndRoundUp() {
        playground.setWithTtl("session", "blob", Duration.ofSeconds(60));
        assertEquals(Optional.of(60L), playground.ttlOf("session"),
                "immediately after SET EX the answer is exactly the armed seconds (ceil)");
        assertTrue(playground.exists("session"));
        assertEquals(Optional.of("blob"), playground.get("session"), "a TTL does not hide the value");
    }

    @Test
    void expireArmsExistingKeysAndRefusesAbsentOnes() {
        assertFalse(playground.expire("ghost", Duration.ofSeconds(30)),
                "EXPIRE on a missing key answers false and changes nothing");
        playground.set("plain", "persistent");
        assertEquals(Optional.empty(), playground.ttlOf("plain"), "a plain SET carries no expiry");
        assertTrue(playground.expire("plain", Duration.ofSeconds(30)));
        assertEquals(Optional.of(30L), playground.ttlOf("plain"));
    }

    @Test
    void expiryIsLazyAbsentOnNextAccess() throws InterruptedException {
        playground.setWithTtl("ephemeral", "gone soon", Duration.ofMillis(60));
        Thread.sleep(120);
        assertEquals(Optional.empty(), playground.get("ephemeral"), "expired means absent on the next read");
        assertFalse(playground.exists("ephemeral"));
        assertEquals(Optional.empty(), playground.ttlOf("ephemeral"));
        assertFalse(playground.keys().contains("guide:demo:ephemeral"),
                "the lazy read reaps the dead entry from memory");
    }

    @Test
    void wrongTypeOperationsFailTheWayTheServerFails() {
        playground.set("stringly", "v");
        assertThrows(IllegalStateException.class, () -> playground.hashGetAll("stringly"),
                "HGETALL on a string is WRONGTYPE");
        playground.hashSet("hashly", "f", "v");
        assertThrows(IllegalStateException.class, () -> playground.get("hashly"),
                "GET on a hash is WRONGTYPE");
    }

    @Test
    void publishShortCircuitsIntoTheSharedRing() {
        long receivers = playground.publish("chan:playground", "hello fake");
        assertEquals(1, receivers, "the fake reports the ring itself as the one subscriber");
        var snapshot = recent.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals("guide:demo:chan:playground", snapshot.getFirst().channel(),
                "the channel is namespaced before it lands in the ring");
        assertEquals("hello fake", snapshot.getFirst().message());
    }

    @Test
    void opsAgainstDifferentStructuresUnderTheSameBareKeyAreDistinctKeys() {
        playground.set("thing", "string");
        playground.hashSet("thing:profile", "f", "v");
        assertEquals(Optional.of("string"), playground.get("thing"));
        assertEquals(Map.of("f", "v"), playground.hashGetAll("thing:profile"));
        assertTrue(playground.keys().containsAll(java.util.Set.of("guide:demo:thing", "guide:demo:thing:profile")));
    }
}
