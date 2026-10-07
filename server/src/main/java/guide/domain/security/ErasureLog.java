package guide.domain.security;

import java.util.List;

/**
 * Port: the append-only erasure audit trail. A right-to-be-forgotten request
 * is a legal event, so it leaves EVIDENCE: which subject, which key died,
 * what was done about the data, and when. Entries are never updated or
 * removed — an erasure log you can edit is not an erasure log.
 */
public interface ErasureLog {

    /** Append one event; the entry carries its own id and timestamp. */
    ErasureLogEntry append(String subjectId, String keyId, String action);

    /** Chronological, immutable view — oldest first. */
    List<ErasureLogEntry> entries();
}
