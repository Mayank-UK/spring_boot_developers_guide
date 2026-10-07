package guide.core;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app's typed configuration record — topic 36 (environments) made real.
 * Every {@code guide.env.*} setting lives in ONE constructor-bound record
 * instead of {@code @Value} needles scattered across the codebase: Boot binds
 * the property sources into this constructor, the compact constructor below
 * normalizes and validates, and a bad value therefore fails the BOOT, never
 * the first request.
 *
 * Demo-scoped by construction: the properties exist to give the env inspector
 * a controlled, teachable set — a safe trio it echoes, one marked-as-secret
 * key it demonstrates REDACTION on. The dev default key is deliberately fake
 * and loudly named; production would set {@code GUIDE_DEMO_API_KEY} from a
 * secret store, and the inspector would still never echo it.
 */
@ConfigurationProperties(prefix = "guide.env")
public record AppConfig(
        /** Where this process believes it runs: local, dev, staging, prod. */
        String deployEnvironment,
        /** A deployment label the inspector echoes as safe demo config. */
        String region,
        /** Example boolean knob: bound from guide.env.maintenance-mode. */
        boolean maintenanceMode,
        /** The marked-as-secret key: fake by default, never echoed by the inspector. */
        String demoApiKey) {

    /** The loudly-fake default — anything equal to this is demo material, not a secret. */
    public static final String DEV_DEFAULT_API_KEY = "demo-only-not-a-real-secret";

    /** The closed set a deploy environment may take — typos fail startup. */
    public static final Set<String> KNOWN_ENVIRONMENTS = Set.of("local", "dev", "staging", "prod");

    public AppConfig {
        deployEnvironment = deployEnvironment == null || deployEnvironment.isBlank()
                ? "local"
                : deployEnvironment.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_ENVIRONMENTS.contains(deployEnvironment)) {
            throw new IllegalStateException("Unknown guide.env.deploy-environment \"" + deployEnvironment
                    + "\" — expected one of " + KNOWN_ENVIRONMENTS
                    + " (invalid config fails the boot, not the first request)");
        }
        region = region == null || region.isBlank() ? "local" : region.trim();
        demoApiKey = demoApiKey == null || demoApiKey.isBlank() ? DEV_DEFAULT_API_KEY : demoApiKey;
    }

    /**
     * The safe verdict the inspector reports INSTEAD of the key's value: is
     * this the known dev default? The answer leaks nothing about a real key
     * while still surfacing the one fact you want at a glance — production
     * forgot to set the env var.
     */
    public boolean demoApiKeyIsDevDefault() {
        return DEV_DEFAULT_API_KEY.equals(demoApiKey);
    }
}
