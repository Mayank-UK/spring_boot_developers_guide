package guide.data.security;

import guide.domain.security.ErasureLog;
import guide.domain.security.ErasureLogEntry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adapter: the append-only erasure log as a synchronized in-memory list.
 * There is no update and no remove method because the PORT has none —
 * append-only is enforced by the type, not by discipline. A production
 * home is a table (or an event stream) with INSERT-only grants; the demo
 * keeps the same contract in memory.
 */
public final class InMemoryErasureLog implements ErasureLog {

    private final List<ErasureLogEntry> entries = new ArrayList<>();
    private final Clock clock;

    public InMemoryErasureLog(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized ErasureLogEntry append(String subjectId, String keyId, String action) {
        ErasureLogEntry entry = new ErasureLogEntry("erasure_" + UUID.randomUUID().toString().substring(0, 8),
                subjectId, keyId, action, clock.instant());
        entries.add(entry);
        return entry;
    }

    @Override
    public synchronized List<ErasureLogEntry> entries() {
        return List.copyOf(entries);
    }
}
