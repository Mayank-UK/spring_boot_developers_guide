package guide.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * The prod fail-fast gate, driven FAST: a stub Environment per case — no
 * context boot, no containers. What is pinned:
 *
 * - prod + either dev-grade secret (the yaml's fake HS256 default, the dev
 *   datasource password, or no value at all) = IllegalStateException with
 *   a message that names the env var to set (loud + actionable);
 * - prod + real values = passes;
 * - dev profile or NO profile + the dev defaults = passes untouched — the
 *   guard must never color the teaching app's own boots;
 * - and two ApplicationContextRunner passes proving the constructor wiring
 *   actually fails (or completes) a context refresh, not just the static
 *   method.
 */
class ProdSanityConfigTest {

    /** A stand-in real secret: right shape (32+ bytes), not the dev literal. */
    private static final String REAL_JWT_SECRET = "a-real-production-secret-0123456789abcdef";

    private static final String REAL_DB_PASSWORD = "a-real-production-password";

    private static MockEnvironment env(String jwtSecret, String dbPassword, String... profiles) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.secret-key", jwtSecret)
                .withProperty("spring.datasource.password", dbPassword);
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        return environment;
    }

    // ---- the gate refuses dev-grade secrets under prod --------------------------

    @Test
    void prodWithTheDevJwtSecretRefusesToBoot() {
        assertThatThrownBy(() -> ProdSanityConfig.verify(
                env(ProdSanityConfig.DEV_JWT_SECRET, REAL_DB_PASSWORD, "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SDG_JWT_SECRET");
    }

    @Test
    void prodWithTheDevDatasourcePasswordRefusesToBoot() {
        assertThatThrownBy(() -> ProdSanityConfig.verify(
                env(REAL_JWT_SECRET, ProdSanityConfig.DEV_DB_PASSWORD, "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void prodWithAnUnsetSecretRefusesToBootRatherThanPassOnNull() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", REAL_DB_PASSWORD);
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> ProdSanityConfig.verify(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SDG_JWT_SECRET");
    }

    // ---- the gate passes exactly where it must ----------------------------------

    @Test
    void prodWithRealSecretsPasses() {
        assertThatCode(() -> ProdSanityConfig.verify(
                env(REAL_JWT_SECRET, REAL_DB_PASSWORD, "prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void devProfileWithBothDevDefaultsPassesUntouched() {
        assertThatCode(() -> ProdSanityConfig.verify(
                env(ProdSanityConfig.DEV_JWT_SECRET, ProdSanityConfig.DEV_DB_PASSWORD, "dev")))
                .doesNotThrowAnyException();
    }

    @Test
    void defaultProfileWithBothDevDefaultsPassesUntouched() {
        assertThatCode(() -> ProdSanityConfig.verify(
                env(ProdSanityConfig.DEV_JWT_SECRET, ProdSanityConfig.DEV_DB_PASSWORD)))
                .doesNotThrowAnyException();
    }

    // ---- the constructor wiring actually gates the context refresh ---------------

    @Test
    void theContextItselfFailsToLoadWhenProdRunsOnTheDevJwtSecret() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.security.oauth2.resourceserver.jwt.secret-key="
                                + ProdSanityConfig.DEV_JWT_SECRET,
                        "spring.datasource.password=" + REAL_DB_PASSWORD)
                .withBean(ProdSanityConfig.class)
                .run(context -> assertThat(context).hasFailed()
                        // the refusal surfaces as the ROOT cause — Spring wraps
                        // constructor throws (BeanCreationException → BeanInstanti
                        // ationException → IllegalStateException) in Framework 7
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .rootCause()
                        .hasMessageContaining("REFUSING TO BOOT")
                        .hasMessageContaining("SDG_JWT_SECRET"));
    }

    @Test
    void theContextLoadsFineUnderDevWithTheDevDefaults() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.security.oauth2.resourceserver.jwt.secret-key="
                                + ProdSanityConfig.DEV_JWT_SECRET,
                        "spring.datasource.password=" + ProdSanityConfig.DEV_DB_PASSWORD)
                .withBean(ProdSanityConfig.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
