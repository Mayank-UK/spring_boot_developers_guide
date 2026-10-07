package guide.domain.auth;

import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;import java.time.Clock;
import java.time.Instant;

/**
 * Issues an API key after proving the requester knows the account's
 * password. The raw key exists exactly once — in this use case's RESULT,
 * shown once by the endpoint, never stored: what lands in the identity
 * store is its SHA-256 plus a short display prefix. Bearer material is a
 * password you show once, not a password you keep.
 */
public final class IssueApiKey {

    private final AuthenticateCredentials authenticate;
    private final IdentityStore identityStore;
    private final Clock clock;

    public IssueApiKey(AuthenticateCredentials authenticate, IdentityStore identityStore, Clock clock) {
        this.authenticate = authenticate;
        this.identityStore = identityStore;
        this.clock = clock;
    }

    public record IssuedKey(String id, String apiKey, String displayPrefix,
            String username, Instant createdAt) {
    }

    public Result<IssuedKey> execute(String username, String password) {
        return switch (authenticate.execute(username, password)) {
            case Success<UserPrincipal> success -> {
                UserPrincipal user = success.value();
                String rawKey = OpaqueTokens.generate(OpaqueTokens.API_KEY_PREFIX);
                String displayPrefix = rawKey.substring(0, OpaqueTokens.API_KEY_PREFIX.length() + 4);
                String id = "key_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                identityStore.saveApiKey(new ApiKeyRecord(
                        id, user.username(), OpaqueTokens.sha256Hex(rawKey),
                        displayPrefix, clock.instant(), null));
                yield Result.success(new IssuedKey(id, rawKey, displayPrefix,
                        user.username(), clock.instant()));
            }
            case Failed<UserPrincipal> failed -> new Failed<>(failed.failure());
        };
    }
}
