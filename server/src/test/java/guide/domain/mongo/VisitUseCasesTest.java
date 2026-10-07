package guide.domain.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.mongo.InMemoryVisitStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three use cases over the seeded fake: validation answers as typed
 * Failures, stats assemble the full snapshot, and the recent feed clamps
 * its bound. All infrastructure-free — the port's fake is just another
 * adapter, which is the port/adapter point in miniature.
 */
class VisitUseCasesTest {

    private InMemoryVisitStore store;
    private RecordVisit recordVisit;
    private ReadVisitStats readVisitStats;
    private ReadRecentVisits readRecentVisits;

    @BeforeEach
    void reset() {
        store = new InMemoryVisitStore();
        recordVisit = new RecordVisit(store);
        readVisitStats = new ReadVisitStats(store);
        readRecentVisits = new ReadRecentVisits(store);
    }

    @Test
    void recordingJudgesInputAndStoresTheHashedVisitor() {
        Result<VisitDocument> stored = recordVisit.execute(new VisitDraft(
                "aop", "/topics/aop", "reader-nine", 42_000, "de-DE"));
        assertTrue(stored instanceof Success<VisitDocument>);
        VisitDocument visit = ((Success<VisitDocument>) stored).value();
        assertEquals(VisitDocument.sha256Hex("reader-nine"), visit.visitorHash(),
                "the token is hashed at the boundary; raw identifiers never persist");
        assertTrue(visit.occurredAt() != null);

        Result<VisitDocument> rejected = recordVisit.execute(null);
        assertTrue(rejected instanceof Failed<VisitDocument>);
        assertEquals(FailureKind.VALIDATION, ((Failed<VisitDocument>) rejected).failure().kind());
    }

    @Test
    void statsSnapshotCarriesEveryAggregateAndTheFilterHonesty() {
        Result<VisitStats> all = readVisitStats.execute(null);
        assertTrue(all instanceof Success<VisitStats>);
        VisitStats stats = ((Success<VisitStats>) all).value();
        assertEquals(24, stats.totalVisits(), "the deterministic seed's size");
        assertEquals(false, stats.filteredByTopic());
        assertEquals(stats.totalVisits(), stats.topicVisits());
        assertEquals(new TopicTally("jdbc-sql", 10), stats.topTopics().getFirst());
        assertEquals(new TopicTally("spring-core", 7), stats.topTopics().get(1));
        assertEquals(ReadVisitStats.TOP_TOPICS_LIMIT, stats.topTopics().size());

        Result<VisitStats> filtered = readVisitStats.execute("aop");
        assertTrue(filtered instanceof Success<VisitStats>);
        VisitStats aop = ((Success<VisitStats>) filtered).value();
        assertEquals(true, aop.filteredByTopic());
        assertEquals(2, aop.topicVisits());
        assertTrue(aop.avgDurationMs() > 0 && aop.avgDurationMs() < 60_000,
                "aop's seeded durations average under a minute");
    }

    @Test
    void malformedTopicFiltersAnswerTheTypedValidation() {
        Result<VisitStats> rejected = readVisitStats.execute("Not_A_Slug");
        assertTrue(rejected instanceof Failed<VisitStats>);
        assertEquals(FailureKind.VALIDATION, ((Failed<VisitStats>) rejected).failure().kind());
    }

    @Test
    void recentFeedJudgesItsBoundAndServesNewestFirst() {
        Result<List<VisitDocument>> rejected = readRecentVisits.execute(0);
        assertTrue(rejected instanceof Failed<List<VisitDocument>>);

        Result<List<VisitDocument>> feed = readRecentVisits.execute(3);
        assertTrue(feed instanceof Success<List<VisitDocument>>);
        List<VisitDocument> visits = ((Success<List<VisitDocument>>) feed).value();
        assertEquals(3, visits.size());
        assertTrue(visits.getFirst().occurredAt().isAfter(visits.getLast().occurredAt()),
                "newest first");
        assertEquals("v24", visits.getFirst().id(), "the seed's newest visit leads");
    }
}
