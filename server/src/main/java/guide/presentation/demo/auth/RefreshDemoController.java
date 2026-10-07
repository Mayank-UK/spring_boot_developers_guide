package guide.presentation.demo.auth;

import guide.core.errors.AppException;
import guide.domain.auth.ExchangeRefreshToken;
import guide.domain.auth.IssueTokenPair;
import guide.domain.auth.RefreshFamilyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 23 (authentication), the refresh island:
 * - POST /api/demo/auth/refresh/issue    {username, password} → an
 *   access+refresh pair (a new token FAMILY is born)
 * - POST /api/demo/auth/refresh/exchange {refreshToken} → the ROTATED
 *   pair (presented token spent, next generation minted); reusing a
 *   spent token revokes the whole family and answers 401
 * - GET  /api/demo/auth/refresh/families → the observability view:
 *   generations per family, spent vs active, revoked flags — hashes
 *   never leave the store
 */
@RestController
public class RefreshDemoController {

    private final IssueTokenPair issueTokenPair;
    private final ExchangeRefreshToken exchangeRefreshToken;
    private final RefreshFamilyStore refreshFamilies;

    public RefreshDemoController(IssueTokenPair issueTokenPair,
            ExchangeRefreshToken exchangeRefreshToken, RefreshFamilyStore refreshFamilies) {
        this.issueTokenPair = issueTokenPair;
        this.exchangeRefreshToken = exchangeRefreshToken;
        this.refreshFamilies = refreshFamilies;
    }

    public record IssueRequest(String username, String password) {
    }

    public record ExchangeRequest(String refreshToken) {
    }

    @PostMapping(value = "/api/demo/auth/refresh/issue",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> issue(@RequestBody IssueRequest request) {
        return switch (issueTokenPair.execute(request.username(), request.password())) {
            case guide.core.result.Success<IssueTokenPair.TokenPair> success ->
                    pairView(success.value(), "issued");
            case guide.core.result.Failed<IssueTokenPair.TokenPair> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    @PostMapping(value = "/api/demo/auth/refresh/exchange",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> exchange(@RequestBody ExchangeRequest request) {
        return switch (exchangeRefreshToken.execute(request.refreshToken())) {
            case guide.core.result.Success<IssueTokenPair.TokenPair> success ->
                    pairView(success.value(), "rotated");
            case guide.core.result.Failed<IssueTokenPair.TokenPair> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    @GetMapping(value = "/api/demo/auth/refresh/families", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> families() {
        List<Map<String, Object>> views = refreshFamilies.families().stream()
                .map(family -> {
                    List<Map<String, Object>> generations = family.generations().stream()
                            .map(generation -> Map.<String, Object>of(
                                    "state", generation.state().name().toLowerCase(
                                            java.util.Locale.ROOT),
                                    "issuedAt", generation.issuedAt().toString()))
                            .toList();
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("familyId", family.familyId());
                    view.put("username", family.username());
                    view.put("revoked", family.revoked());
                    view.put("generations", generations);
                    return view;
                })
                .toList();
        return Map.of("families", views);
    }

    private static Map<String, Object> pairView(IssueTokenPair.TokenPair pair, String action) {
        return Map.of(
                "action", action,
                "accessToken", pair.accessToken(),
                "accessTokenExpiresAt", pair.accessTokenExpiresAt().toString(),
                "refreshToken", pair.refreshToken(),
                "familyId", pair.familyId());
    }
}
