package guide.domain.authz;

import java.util.List;
import java.util.Optional;

/**
 * The document substrate port (topic 24's authorization objects). The
 * in-memory adapter (data/authz) seeds documents owned by alice, bob and
 * carol across all three visibilities; a real adapter is a repository
 * behind the same two questions plus one write. Authorization logic NEVER
 * touches the adapter — it asks this port for objects and decides on the
 * domain records it gets back.
 */
public interface DocumentStore {

    /** One document by id — the object an object-level check reasons about. */
    Optional<OwnedDocument> findById(String id);

    /** All documents, UNFILTERED — filtering is policy work, not store work. */
    List<OwnedDocument> findAll();

    /** Persist a document (the publish transition writes the new visibility). */
    void save(OwnedDocument document);
}
