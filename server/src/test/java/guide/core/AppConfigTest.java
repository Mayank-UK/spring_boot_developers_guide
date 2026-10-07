package guide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The typed config record's own rules, tested as a plain value — no Spring
 * context, exactly the testability the record form buys: defaults
 * normalize, the deploy environment is validated against its closed set (a
 * typo fails construction — at boot, the boot fails), and the secret
 * verdict answers without ever exposing the value.
 */
class AppConfigTest {

    @Test
    void blanksNormalizeToTheDocumentedDefaults() {
        AppConfig config = new AppConfig(null, "  ", false, null);
        assertEquals("local", config.deployEnvironment());
        assertEquals("local", config.region());
        assertFalse(config.maintenanceMode());
        assertEquals(AppConfig.DEV_DEFAULT_API_KEY, config.demoApiKey());
        assertTrue(config.demoApiKeyIsDevDefault());
    }

    @Test
    void theDeployEnvironmentNormalizesCaseAndWhitespaceBeforeValidation() {
        assertEquals("dev", new AppConfig("  DEV ", null, false, null).deployEnvironment());
        assertEquals("prod", new AppConfig("prod", "eu-central-1", true, "real-key").deployEnvironment());
        assertFalse(new AppConfig("prod", null, false, "real-key").demoApiKeyIsDevDefault());
    }

    @Test
    void anUnknownDeployEnvironmentFailsConstructionWithANamingMessage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AppConfig("produktion", null, false, null));
        assertTrue(error.getMessage().contains("guide.env.deploy-environment"));
        assertTrue(error.getMessage().contains("produktion"));
        assertTrue(error.getMessage().contains("local"));
    }

    @Test
    void aBlankSecretFallsBackToTheLoudlyFakeDefaultNeverToNull() {
        AppConfig config = new AppConfig("local", "local", false, "   ");
        assertEquals(AppConfig.DEV_DEFAULT_API_KEY, config.demoApiKey());
        assertTrue(config.demoApiKeyIsDevDefault());
    }
}
