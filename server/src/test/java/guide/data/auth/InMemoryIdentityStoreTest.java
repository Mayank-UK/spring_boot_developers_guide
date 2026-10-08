package guide.data.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.auth.ApiKeyRecord;
import guide.domain.auth.OpaqueTokens;
import guide.domain.auth.UserPrincipal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The identity substrate, unit-level: seeded users verify against
 * STARTUP-MINTED bcrypt hashes (not literals), and API keys live only
 * as SHA-256 hashes with display prefixes.
 */
class InMemoryIdentityStoreTest {

    private final InMemoryIdentityStore store =
            new InMemoryIdentityStore(new BCryptPasswordEncoder());

    @Test
    void seededUsersCarryTheCurriculumRoles() {
        assertEquals(Optional.of(java.util.List.of("admin", "staff")),
                store.findByUsername("alice").map(UserPrincipal::roles));
        assertEquals(Optional.of(java.util.List.of("staff")),
                store.findByUsername("bob").map(UserPrincipal::roles));
        assertEquals(Optional.of(java.util.List.of("reader")),
                store.findByUsername("carol").map(UserPrincipal::roles));
    }

    @Test
    void seededHashesAreRealBcryptAndVerify() {
        UserPrincipal alice = store.findByUsername("alice").orElseThrow();
        assertTrue(alice.passwordHash().startsWith("$2a$"));
        assertTrue(new BCryptPasswordEncoder()
                .matches(InMemoryIdentityStore.ALICE_PASSWORD, alice.passwordHash()));
        assertFalse(new BCryptPasswordEncoder()
                .matches("wrong", alice.passwordHash()));
    }

    @Test
    void keysAreStoredHashedAndLookUpByTheirHash() {
        String rawKey = OpaqueTokens.generate(OpaqueTokens.API_KEY_PREFIX);
        store.saveApiKey(new ApiKeyRecord("key_test1", "alice",
                OpaqueTokens.sha256Hex(rawKey), "sdg_key_test…",
                Instant.parse("2026-10-06T12:00:00Z"), null));

        Optional<ApiKeyRecord> found = store.findApiKeyByHash(OpaqueTokens.sha256Hex(rawKey));
        assertTrue(found.isPresent());
        assertEquals("alice", found.orElseThrow().username());
        // the raw key is nowhere in the store's answer
        assertFalse(found.orElseThrow().toString().contains(rawKey));
        assertEquals(Optional.empty(), store.findApiKeyByHash(OpaqueTokens.sha256Hex("sdg_key_other")));
    }

    @Test
    void revocationRemovesTheKeyFromBothIndexes() {
        String rawKey = OpaqueTokens.generate(OpaqueTokens.API_KEY_PREFIX);
        store.saveApiKey(new ApiKeyRecord("key_test2", "bob",
                OpaqueTokens.sha256Hex(rawKey), "sdg_key_x…",
                Instant.parse("2026-10-06T12:00:00Z"), null));
        assertTrue(store.revokeApiKey("key_test2"));
        assertFalse(store.revokeApiKey("key_test2"));
        assertEquals(Optional.empty(), store.findApiKeyByHash(OpaqueTokens.sha256Hex(rawKey)));
    }
}
