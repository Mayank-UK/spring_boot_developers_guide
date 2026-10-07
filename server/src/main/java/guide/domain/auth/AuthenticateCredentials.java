package guide.domain.auth;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one credential check every login-shaped endpoint funnels through:
 * look the user up, then verify the password AGAINST THE STORED HASH. Two
 * deliberate details:
 *
 * - the FAILURE is one shape for both "no such user" and "wrong password"
 *   (UNAUTHORIZED, no detail about which side failed) — an endpoint that
 *   answers differently for unknown users hands out a username oracle.
 * - the encoder is spring-security-crypto's PasswordEncoder, injected —
 *   the same bean the filter chain's DaoAuthenticationProvider uses, so
 *   this use case and the framework can never disagree about hash format.
 */
public final class AuthenticateCredentials {

    private final IdentityStore identityStore;
    private final PasswordEncoder passwordEncoder;

    public AuthenticateCredentials(IdentityStore identityStore, PasswordEncoder passwordEncoder) {
        this.identityStore = identityStore;
        this.passwordEncoder = passwordEncoder;
    }

    public Result<UserPrincipal> execute(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "username and password are both required"));
        }
        return identityStore.findByUsername(username)
                .filter(user -> passwordEncoder.matches(password, user.passwordHash()))
                .<Result<UserPrincipal>>map(user -> Result.success(user))
                .orElseGet(() -> new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                        "invalid credentials")));
    }
}
