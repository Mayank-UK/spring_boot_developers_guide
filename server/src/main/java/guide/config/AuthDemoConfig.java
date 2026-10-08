package guide.config;

import guide.data.auth.InMemoryAuthorizationCodeStore;
import guide.data.auth.InMemoryIdentityStore;
import guide.data.auth.InMemoryRefreshFamilyStore;
import guide.domain.auth.AuthenticateCredentials;
import guide.domain.auth.AuthorizationCodeStore;
import guide.domain.auth.AuthorizeClient;
import guide.domain.auth.ExchangeAuthorizationCode;
import guide.domain.auth.ExchangeRefreshToken;
import guide.domain.auth.IdentityStore;
import guide.domain.auth.IssueApiKey;
import guide.domain.auth.IssueTokenPair;
import guide.domain.auth.JwtMinter;
import guide.domain.auth.KnownClients;
import guide.domain.auth.PasswordHashLab;
import guide.domain.auth.RefreshFamilyStore;
import guide.presentation.security.SecuritySessionService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Demo wiring for topic 23 (authentication) — every auth use case as an
 * explicit bean, ports to adapters, constructor injection throughout.
 *
 * Notable choices:
 * - The in-memory identity substrate is seeded HERE (alice/bob/carol,
 *   bcrypt hashes minted at startup from dev constants — see
 *   InMemoryIdentityStore's javadoc).
 * - The JwtMinter reads the SAME secret property the resource server
 *   validates with (spring.security.oauth2.resourceserver.jwt
 *   .secret-key) — one source of truth for the HS256 key, mint and
 *   verify can never drift apart.
 * - Clock is not a bean (WebhooksDemoConfig precedent): production wants
 *   system UTC; classes take Clock in constructors, the seam tests use.
 */
@Configuration
public class AuthDemoConfig {

    @Bean
    IdentityStore identityStore(PasswordEncoder passwordEncoder) {
        return new InMemoryIdentityStore(passwordEncoder);
    }

    @Bean
    RefreshFamilyStore refreshFamilyStore() {
        return new InMemoryRefreshFamilyStore();
    }

    @Bean
    AuthorizationCodeStore authorizationCodeStore() {
        return new InMemoryAuthorizationCodeStore();
    }

    @Bean
    KnownClients knownClients() {
        return new KnownClients();
    }

    @Bean
    AuthenticateCredentials authenticateCredentials(IdentityStore identityStore,
            PasswordEncoder passwordEncoder) {
        return new AuthenticateCredentials(identityStore, passwordEncoder);
    }

    @Bean
    JwtMinter jwtMinter(
            @Value("${spring.security.oauth2.resourceserver.jwt.secret-key}") String hs256Secret) {
        return new JwtMinter(hs256Secret, Clock.systemUTC());
    }

    /**
     * The verifying side, built explicitly instead of left to Boot's
     * secret-key autoconfiguration for ONE deliberate reason: the default
     * JwtTimestampValidator tolerates 60 SECONDS of clock skew, and this
     * topic's expiry demo mints tokens that die in seconds — a teaching
     * decoder narrows the skew to 1s so the tour can watch a token
     * actually expire. Same secret property as the minter; mint and
     * verify can never drift apart.
     */
    @Bean
    org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.secret-key}") String hs256Secret) {
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withSecretKey(
                        new javax.crypto.spec.SecretKeySpec(
                                hs256Secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "HmacSHA256"))
                        .build();
        decoder.setJwtValidator(
                new org.springframework.security.oauth2.jwt.JwtTimestampValidator(
                        java.time.Duration.ofSeconds(1)));
        return decoder;
    }

    @Bean
    SecuritySessionService securitySessionService(AuthenticateCredentials authenticate) {
        return new SecuritySessionService(authenticate);
    }

    @Bean
    IssueApiKey issueApiKey(AuthenticateCredentials authenticate, IdentityStore identityStore) {
        return new IssueApiKey(authenticate, identityStore, Clock.systemUTC());
    }

    @Bean
    IssueTokenPair issueTokenPair(AuthenticateCredentials authenticate, IdentityStore identityStore,
            RefreshFamilyStore refreshFamilies, JwtMinter jwtMinter) {
        return new IssueTokenPair(authenticate, identityStore, refreshFamilies, jwtMinter,
                Clock.systemUTC());
    }

    @Bean
    ExchangeRefreshToken exchangeRefreshToken(RefreshFamilyStore refreshFamilies,
            IdentityStore identityStore, JwtMinter jwtMinter) {
        return new ExchangeRefreshToken(refreshFamilies, identityStore, jwtMinter, Clock.systemUTC());
    }

    @Bean
    AuthorizeClient authorizeClient(KnownClients clients, AuthenticateCredentials authenticate,
            AuthorizationCodeStore codeStore) {
        return new AuthorizeClient(clients, authenticate, codeStore, Clock.systemUTC());
    }

    @Bean
    ExchangeAuthorizationCode exchangeAuthorizationCode(AuthorizationCodeStore codeStore,
            IssueTokenPair issueTokenPair) {
        return new ExchangeAuthorizationCode(codeStore, issueTokenPair, Clock.systemUTC());
    }

    @Bean
    PasswordHashLab passwordHashLab() {
        return new PasswordHashLab();
    }
}
