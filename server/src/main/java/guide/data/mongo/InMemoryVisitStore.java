package guide.data.mongo;

import guide.domain.mongo.LocaleTally;
import guide.domain.mongo.TopicTally;
import guide.domain.mongo.VisitAggregate;
import guide.domain.mongo.VisitDocument;
import guide.domain.mongo.VisitDraft;
import guide.domain.mongo.VisitStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;

/**
 * The in-memory VisitStore ADAPTER: the fake that answers the port while
 * MongoDB is absent — bounded, thread-safe, deterministic. BOUNDED because
 * a visit feed only ever needs a window on recent history (the live store
 * gets the same bound from its TTL index — memory and disk agree that
 * analytics data expires); the oldest visits fall off the front once the
 * cap is hit, so a demo left recording forever cannot grow the heap.
 * THREAD-SAFE by one rule: mutations append under a monitor; every read
 * snapshots the list first and computes aggregates over the immutable
 * snapshot — VisitAggregate's pure functions, the same math the MongoDB
 * pipelines must reproduce.
 */
public class InMemoryVisitStore implements VisitStore {

    /** The window: the most recent visits the fake keeps (the TTL story in miniature). */
    public static final int CAPACITY = 500;

    private final List<VisitDocument> seed;
    private final List<VisitDocument> visits;
    private final Object mutationMonitor = new Object();

    public InMemoryVisitStore() {
        this(SeedVisits.list());
    }

    public InMemoryVisitStore(List<VisitDocument> seed) {
        this.seed = List.copyOf(seed);
        this.visits = new ArrayList<>(seed);
    }

    @Override
    public VisitDocument record(VisitDraft draft) {
        synchronized (mutationMonitor) {
            // Identity minted in the adapter, exactly like the live store's
            // ObjectId: the caller's draft has no say in its own id.
            VisitDocument stored = new VisitDocument(
                    "visit-" + UUID.randomUUID(),
                    draft.topicId(), draft.path(),
                    VisitDocument.sha256Hex(draft.visitorToken()),
                    draft.durationMs(), Instant.now(), draft.locale());
            visits.add(stored);
            if (visits.size() > CAPACITY) {
                visits.subList(0, visits.size() - CAPACITY).clear();
            }
            return stored;
        }
    }

    @Override
    public List<VisitDocument> recent(int limit) {
        return snapshot().stream()
                .sorted(Comparator.comparing(VisitDocument::occurredAt).reversed()
                        .thenComparing(VisitDocument::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public long countAll() {
        return snapshot().size();
    }

    @Override
    public long countByTopic(String topicId) {
        return snapshot().stream().filter(visit -> visit.topicId().equals(topicId)).count();
    }

    @Override
    public List<TopicTally> topTopics(int limit) {
        return VisitAggregate.topTopics(snapshot(), limit);
    }

    @Override
    public long avgDurationMs(String topicId) {
        return VisitAggregate.avgDurationMs(snapshot(), topicId);
    }

    @Override
    public List<LocaleTally> byLocaleHistogram() {
        return VisitAggregate.byLocaleHistogram(snapshot());
    }

    /** Restores the deterministic seed — what every boot starts from, and tests re-start from. */
    public void reset() {
        synchronized (mutationMonitor) {
            visits.clear();
            visits.addAll(seed);
        }
    }

    /** An immutable copy under the monitor — reads never hold the lock while computing. */
    private List<VisitDocument> snapshot() {
        synchronized (mutationMonitor) {
            return List.copyOf(visits);
        }
    }

    /** Drop every visit older than the given instant — the TTL index's behavior, testable. */
    public int expireOlderThan(Instant cutoff) {
        synchronized (mutationMonitor) {
            int before = visits.size();
            ListIterator<VisitDocument> iterator = visits.listIterator();
            while (iterator.hasNext()) {
                if (iterator.next().occurredAt().isBefore(cutoff)) {
                    iterator.remove();
                }
            }
            return before - visits.size();
        }
    }
}
