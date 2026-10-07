package guide.data.security;

import guide.domain.security.PiiRecord;
import guide.domain.security.PiiVault;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter: the ciphertext store as an in-memory map. It knows nothing about
 * keys — that amnesia is the design (see the PiiVault port). In the demo's
 * before/after story this is deliberately the component that KEEPS its rows
 * across an erasure: the store is not asked to delete anything, which is
 * exactly what makes the pattern survive backups and replicas in real
 * systems.
 */
public final class InMemoryPiiVault implements PiiVault {

    private final Map<String, PiiRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(PiiRecord record) {
        records.put(record.subjectId(), record);
    }

    @Override
    public Optional<PiiRecord> find(String subjectId) {
        return Optional.ofNullable(records.get(subjectId));
    }
}
