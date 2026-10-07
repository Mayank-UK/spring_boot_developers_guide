package guide.data.mongo;

import guide.domain.mongo.VisitDocument;
import java.time.Instant;
import java.util.List;

/**
 * The deterministic seed behind the mongo console's fake mode: twenty-four
 * visits with HARDCODED ids, instants, durations, and visitor hashes —
 * byte-identical on every boot, in every container, so tests assert exact
 * tallies and the demo always opens on a populated, believable console.
 *
 * The population is deliberately lopsided: jdbc-sql and spring-core lead,
 * en/de-DE/ja/fr locales spread unevenly, and durations range 8s–14min —
 * aggregates with interesting shape (a clear winner, a tie for second, a
 * long tail), because a uniform seed teaches nothing. Visitor tokens are
 * hashed at seed-build time via the SAME VisitDocument.sha256Hex seam the
 * live path uses, so no raw token exists anywhere (six recurring readers:
 * returning traffic, visible as repeated hashes).
 */
public final class SeedVisits {

    private SeedVisits() {
    }

    /** The seed, oldest first (the store renders newest-first views). */
    public static List<VisitDocument> list() {
        return List.of(
                visit("v01", "jdbc-sql", "/topics/jdbc-sql", "reader-one", 84_000,
                        "2026-09-20T09:03:00Z", "en"),
                visit("v02", "spring-core", "/topics/spring-core", "reader-two", 412_000,
                        "2026-09-20T09:11:00Z", "en"),
                visit("v03", "jdbc-sql", "/topics/jdbc-sql", "reader-three", 126_000,
                        "2026-09-20T10:02:00Z", "de-DE"),
                visit("v04", "routing", "/topics/routing", "reader-one", 47_000,
                        "2026-09-20T11:40:00Z", "en"),
                visit("v05", "jdbc-sql", "/topics/jdbc-sql", "reader-two", 301_000,
                        "2026-09-21T08:15:00Z", "en"),
                visit("v06", "spring-core", "/topics/spring-core", "reader-four", 233_000,
                        "2026-09-21T09:00:00Z", "ja"),
                visit("v07", "validation", "/topics/validation", "reader-five", 65_000,
                        "2026-09-21T13:26:00Z", "fr"),
                visit("v08", "jdbc-sql", "/topics/jdbc-sql", "reader-one", 97_000,
                        "2026-09-22T07:58:00Z", "en"),
                visit("v09", "spring-core", "/topics/spring-core", "reader-one", 388_000,
                        "2026-09-22T08:44:00Z", "en"),
                visit("v10", "aop", "/topics/aop", "reader-six", 59_000,
                        "2026-09-22T15:09:00Z", "de-DE"),
                visit("v11", "jdbc-sql", "/topics/jdbc-sql", "reader-four", 154_000,
                        "2026-09-23T06:30:00Z", "en"),
                visit("v12", "spring-core", "/topics/spring-core", "reader-three", 267_000,
                        "2026-09-23T10:17:00Z", "de-DE"),
                visit("v13", "jdbc-sql", "/topics/jdbc-sql", "reader-five", 8_000,
                        "2026-09-23T18:52:00Z", "en"),
                visit("v14", "validation", "/topics/validation", "reader-one", 73_000,
                        "2026-09-24T09:05:00Z", "en"),
                visit("v15", "spring-core", "/topics/spring-core", "reader-two", 501_000,
                        "2026-09-24T11:31:00Z", "ja"),
                visit("v16", "jdbc-sql", "/topics/jdbc-sql", "reader-six", 199_000,
                        "2026-09-25T07:12:00Z", "fr"),
                visit("v17", "aop", "/topics/aop", "reader-three", 44_000,
                        "2026-09-25T12:48:00Z", "en"),
                visit("v18", "jdbc-sql", "/topics/jdbc-sql", "reader-three", 112_000,
                        "2026-09-26T08:20:00Z", "en"),
                visit("v19", "spring-core", "/topics/spring-core", "reader-four", 342_000,
                        "2026-09-26T09:56:00Z", "de-DE"),
                visit("v20", "routing", "/topics/routing", "reader-two", 51_000,
                        "2026-09-26T14:33:00Z", "ja"),
                visit("v21", "jdbc-sql", "/topics/jdbc-sql", "reader-two", 268_000,
                        "2026-09-27T07:44:00Z", "en"),
                visit("v22", "validation", "/topics/validation", "reader-five", 81_000,
                        "2026-09-27T13:19:00Z", "fr"),
                visit("v23", "spring-core", "/topics/spring-core", "reader-one", 405_000,
                        "2026-09-28T08:01:00Z", "en"),
                visit("v24", "jdbc-sql", "/topics/jdbc-sql", "reader-four", 137_000,
                        "2026-09-28T10:27:00Z", "en"));
    }

    private static VisitDocument visit(String id, String topicId, String path, String token,
            long durationMs, String occurredAt, String locale) {
        return new VisitDocument(id, topicId, path, VisitDocument.sha256Hex(token),
                durationMs, Instant.parse(occurredAt), locale);
    }
}
