package guide.data.auth;

import guide.domain.auth.RefreshFamilyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter: refresh-token families, in memory, bounded. Tokens are stored
 * by SHA-256 hash and STAY in the map after rotation — a spent token must
 * remain resolvable so its replay can be recognized and its family
 * revoked (deleting it would turn the theft signal into a mere "unknown
 * token"). Families evict oldest-first at the ceiling; a revoked family's
 * hashes stay until eviction for the same reason.
 */
public final class InMemoryRefreshFamilyStore implements RefreshFamilyStore {

    static final int MAX_FAMILIES = 32;

    private record Family(String id, String username, boolean revoked,
            List<Generation> generations) {

        record Generation(String tokenHash, StoredRefreshToken.TokenState state, Instant issuedAt) {
        }
    }

    private final Map<String, Family> families = new LinkedHashMap<>();

    @Override
    public synchronized void startFamily(String familyId, String username,
            String firstTokenHash, Instant at) {
        List<Family.Generation> generations = new ArrayList<>();
        generations.add(new Family.Generation(firstTokenHash,
                StoredRefreshToken.TokenState.ACTIVE, at));
        families.put(familyId, new Family(familyId, username, false, generations));
        while (families.size() > MAX_FAMILIES) {
            families.remove(families.keySet().iterator().next());
        }
    }

    @Override
    public synchronized StoredRefreshToken findByTokenHash(String tokenHash) {
        for (Family family : families.values()) {
            for (Family.Generation generation : family.generations()) {
                if (generation.tokenHash().equals(tokenHash)) {
                    return new StoredRefreshToken(family.id(), family.username(),
                            generation.state(), family.revoked());
                }
            }
        }
        return null;
    }

    @Override
    public synchronized void rotate(String familyId, String spentTokenHash,
            String nextTokenHash, Instant at) {
        Family family = families.get(familyId);
        if (family == null) {
            throw new IllegalStateException("family " + familyId + " vanished mid-rotation");
        }
        List<Family.Generation> generations = new ArrayList<>();
        for (Family.Generation generation : family.generations()) {
            generations.add(generation.tokenHash().equals(spentTokenHash)
                    ? new Family.Generation(generation.tokenHash(),
                            StoredRefreshToken.TokenState.ROTATED, generation.issuedAt())
                    : generation);
        }
        generations.add(new Family.Generation(nextTokenHash,
                StoredRefreshToken.TokenState.ACTIVE, at));
        families.put(familyId, new Family(family.id(), family.username(),
                family.revoked(), generations));
    }

    @Override
    public synchronized void revokeFamily(String familyId) {
        Family family = families.get(familyId);
        if (family != null) {
            families.put(familyId, new Family(family.id(), family.username(),
                    true, family.generations()));
        }
    }

    @Override
    public synchronized List<RefreshFamilyView> families() {
        List<RefreshFamilyView> views = new ArrayList<>();
        for (Family family : families.values()) {
            List<RefreshFamilyView.Generation> generations = family.generations().stream()
                    .map(generation -> new RefreshFamilyView.Generation(
                            generation.state(), generation.issuedAt()))
                    .toList();
            views.add(new RefreshFamilyView(family.id(), family.username(),
                    family.revoked(), generations));
        }
        return views;
    }
}
