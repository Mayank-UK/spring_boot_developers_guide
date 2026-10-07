package guide.presentation.demo.auth;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.auth.ApiKeyRecord;
import guide.domain.auth.IdentityStore;
import guide.domain.auth.IssueApiKey;
import guide.domain.auth.UserPrincipal;
import guide.presentation.security.SecuritySessionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 23 (authentication), the API-key island:
 * - POST   /api/demo/auth/apikeys         {username, password} → issue; the
 *   RAW KEY appears in exactly this response (password proof, shown once)
 * - GET    /api/demo/auth/apikeys         → the redacted list (chain:
 *   authenticated — a key or a session reads it)
 * - DELETE /api/demo/auth/apikeys/{id}    → revoke
 * - GET    /api/demo/auth/apikeys/whoami  → who the presented X-API-Key
 *   identifies — the proof the key IS a principal (chain: authenticated)
 */
@RestController
public class ApiKeysDemoController {

    private final IssueApiKey issueApiKey;
    private final IdentityStore identityStore;
    private final SecuritySessionService sessions;

    public ApiKeysDemoController(IssueApiKey issueApiKey, IdentityStore identityStore,
            SecuritySessionService sessions) {
        this.issueApiKey = issueApiKey;
        this.identityStore = identityStore;
        this.sessions = sessions;
    }

    public record IssueRequest(String username, String password) {
    }

    @PostMapping(value = "/api/demo/auth/apikeys",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> issue(@RequestBody IssueRequest request) {
        Result<IssueApiKey.IssuedKey> result = issueApiKey.execute(request.username(), request.password());
        return switch (result) {
            case guide.core.result.Success<IssueApiKey.IssuedKey> success -> {
                IssueApiKey.IssuedKey issued = success.value();
                yield Map.of(
                        "id", issued.id(),
                        "apiKey", issued.apiKey(),
                        "note", "shown once — store it now; only its hash is kept",
                        "displayPrefix", issued.displayPrefix(),
                        "username", issued.username());
            }
            case guide.core.result.Failed<IssueApiKey.IssuedKey> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    @GetMapping(value = "/api/demo/auth/apikeys", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list() {
        List<Map<String, Object>> keys = identityStore.apiKeys().stream()
                .map(ApiKeysDemoController::redactedView)
                .toList();
        return Map.of("keys", keys);
    }

    @DeleteMapping(value = "/api/demo/auth/apikeys/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> revoke(@PathVariable String id) {
        if (!identityStore.revokeApiKey(id)) {
            throw new AppException(Failure.of(FailureKind.NOT_FOUND, "unknown key id: " + id));
        }
        return Map.of("revoked", true, "id", id);
    }

    @GetMapping(value = "/api/demo/auth/apikeys/whoami", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> whoami() {
        UserPrincipal user = sessions.currentUser();
        if (user == null) {
            throw new AppException(Failure.of(FailureKind.UNAUTHORIZED,
                    "no authenticated principal — send X-API-Key"));
        }
        return Map.of(
                "user", Map.of("id", user.id(), "username", user.username(),
                        "roles", List.copyOf(user.roles())),
                "via", sessions.authenticatedVia());
    }

    private static Map<String, Object> redactedView(ApiKeyRecord record) {
        return Map.of(
                "id", record.id(),
                "redacted", record.redacted(),
                "username", record.username(),
                "createdAt", record.createdAt().toString(),
                "lastUsedAt", record.lastUsedAt() == null ? "" : record.lastUsedAt().toString());
    }
}
