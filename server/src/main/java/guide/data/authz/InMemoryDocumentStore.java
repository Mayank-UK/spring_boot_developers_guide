package guide.data.authz;

import guide.domain.authz.DocumentStore;
import guide.domain.authz.OwnedDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter: the authorization substrate's documents, in memory. Seeded
 * across all three owners and all three visibilities so every policy
 * branch has a row that exercises it: alice owns a private and a
 * team doc, bob a private and a team doc, carol a private doc and the
 * one PUBLIC document. publish() transitions visibility through the
 * port's save — the store keeps no logic of its own.
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, OwnedDocument> documentsById = new LinkedHashMap<>();

    public InMemoryDocumentStore() {
        seed("doc-alice-private", "alice", "Launch checklist (draft)",
                "Pre-launch gates: migrations green, smoke suite, rollback rehearsed.",
                OwnedDocument.Visibility.PRIVATE);
        seed("doc-alice-team", "alice", "Staff onboarding guide",
                "Week one: environment, review etiquette, demo island tour.",
                OwnedDocument.Visibility.TEAM);
        seed("doc-bob-private", "bob", "Sprint retro notes",
                "What went well: smaller PRs. What did not: flaky fixtures.",
                OwnedDocument.Visibility.PRIVATE);
        seed("doc-bob-team", "bob", "Incident runbook",
                "Sev1: page, stabilize, timeline, blameless postmortem within 48h.",
                OwnedDocument.Visibility.TEAM);
        seed("doc-carol-private", "carol", "Reading list",
                "RFC 9457, OWASP API Top 10, the CQRS papers.",
                OwnedDocument.Visibility.PRIVATE);
        seed("doc-roadmap", "carol", "Public roadmap",
                "Next quarter: authz islands for every demo, one audit log.",
                OwnedDocument.Visibility.PUBLIC);
    }

    private void seed(String id, String ownerId, String title, String body,
            OwnedDocument.Visibility visibility) {
        documentsById.put(id, new OwnedDocument(id, ownerId, title, body, visibility));
    }

    @Override
    public synchronized Optional<OwnedDocument> findById(String id) {
        return Optional.ofNullable(documentsById.get(id));
    }

    @Override
    public synchronized List<OwnedDocument> findAll() {
        return List.copyOf(documentsById.values());
    }

    @Override
    public synchronized void save(OwnedDocument document) {
        documentsById.put(document.id(), document);
    }
}
