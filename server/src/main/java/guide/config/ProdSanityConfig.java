package guide.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Production posture, enforced at boot: when the `prod` profile is active,
 * the application REFUSES to start on dev-grade secrets — the yaml's
 * loudly-fake HS256 default and the dev datasource password — because
 * fail-fast beats fail-weird: a "prod" that silently runs on a secret every
 * reader of this repo holds is not degraded, it is compromised, and a boot
 * refusal with an actionable message is the cheapest possible time to find
 * out. Dev, devcontainer, and the default profile are untouched: the check
 * is a no-op unless `prod` is ACTIVE, so the teaching app keeps booting
 * everywhere it always did.
 *
 * The mechanism is deliberately dull — read the Environment in the
 * configuration's constructor (bean-creation time, so the context refresh
 * fails) and compare against the two dev literals — because a guard you
 * can explain in one sentence is a guard that survives a refactor. The
 * third leg lives in .docker/docker-compose.prod.yml: compose's required-
 * variable syntax (`${SDG_JWT_SECRET:?…}`) makes the missing secret a
 * deploy-time failure BEFORE any container starts.
 */
@Configuration
public class ProdSanityConfig {

    /** The application.yml DEV default for the HS256 key — never a prod secret. */
    public static final String DEV_JWT_SECRET = "dev-only-sdg-jwt-hs256-secret-3b9d6f01c2a8e45d7f0b";

    /** The dev compose stacks' datasource password — ditto. */
    public static final String DEV_DB_PASSWORD = "guide";

    public ProdSanityConfig(Environment environment) {
        verify(environment);
    }

    /**
     * The whole gate, static so a test can drive it with a stub Environment:
     * no-op unless `prod` is active; throws IllegalStateException naming the
     * env var to set when a dev-grade value (or no value at all) is found.
     */
    public static void verify(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String jwtSecret = environment.getProperty(
                "spring.security.oauth2.resourceserver.jwt.secret-key");
        if (jwtSecret == null || jwtSecret.equals(DEV_JWT_SECRET)) {
            throw new IllegalStateException(
                    "REFUSING TO BOOT: the prod profile is active but the JWT signing key is the "
                            + "application.yml DEV default (or unset) — anyone who has read this "
                            + "repo could mint tokens prod accepts. Set SDG_JWT_SECRET to a real "
                            + "32+ byte secret from your secret store and restart. Fail-fast beats "
                            + "fail-weird.");
        }
        String dbPassword = environment.getProperty("spring.datasource.password");
        if (dbPassword == null || dbPassword.equals(DEV_DB_PASSWORD)) {
            throw new IllegalStateException(
                    "REFUSING TO BOOT: the prod profile is active but the datasource password is "
                            + "'guide' (or unset) — the repo's dev-compose value, not a prod "
                            + "credential. Set SPRING_DATASOURCE_PASSWORD to the real password "
                            + "and restart. Fail-fast beats fail-weird.");
        }
    }
}
