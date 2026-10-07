package guide.domain.environments;

import guide.core.AppConfig;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import java.util.List;
import java.util.Objects;

/**
 * Use case behind the env inspector demo (topic 36): report the environment
 * story for a controlled, teachable set of facts — the active profiles, a
 * curated list of resolved {@code @ConfigurationProperties} values with each
 * classified safe vs secret, and the runtime-switchable bean variants
 * (the ScenarioStore flags this app really has).
 *
 * Redaction is structural, not cosmetic: the secret key's value has NO code
 * path into the answer. The only fact reported about it is
 * {@link AppConfig#demoApiKeyIsDevDefault()} — a boolean that cannot leak a
 * real value and still surfaces the one operational fact worth surfacing:
 * production forgot to set the env var. Deterministic by construction: every
 * input is process state that exists before the first request.
 */
public final class InspectEnvironment {

    /** What a classified-secret property line carries instead of its value. */
    public static final String REDACTED = "<redacted>";

    public static final String SAFE = "safe";
    public static final String SECRET = "secret";

    private final AppConfig config;
    private final ActiveProfiles activeProfiles;
    private final ScenarioStore scenario;

    public InspectEnvironment(AppConfig config, ActiveProfiles activeProfiles, ScenarioStore scenario) {
        this.config = Objects.requireNonNull(config, "config");
        this.activeProfiles = Objects.requireNonNull(activeProfiles, "activeProfiles");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
    }

    public EnvInspection execute() {
        List<EnvInspection.PropertyLine> properties = List.of(
                new EnvInspection.PropertyLine("guide.env.deploy-environment",
                        config.deployEnvironment(), SAFE),
                new EnvInspection.PropertyLine("guide.env.region", config.region(), SAFE),
                new EnvInspection.PropertyLine("guide.env.maintenance-mode",
                        String.valueOf(config.maintenanceMode()), SAFE),
                new EnvInspection.PropertyLine("guide.env.demo-api-key", REDACTED, SECRET));
        boolean devDefault = config.demoApiKeyIsDevDefault();
        ScenarioDataMode mode = scenario.get().dataMode();
        List<EnvInspection.FlagLine> flags = List.of(
                new EnvInspection.FlagLine("scenario.data-mode", mode.wireName(), true,
                        "GET/PATCH /api/demo/scenario — flips without a restart"),
                new EnvInspection.FlagLine("scenario.inject-failure",
                        String.valueOf(scenario.get().injectFailure()), true,
                        "GET/PATCH /api/demo/scenario — the failure-injection switch"));
        return new EnvInspection(activeProfiles.names(), properties, devDefault, flags,
                reading(mode, devDefault));
    }

    /** Narration lines: the report explains itself the way the topic's prose does. */
    private List<String> reading(ScenarioDataMode mode, boolean secretIsDevDefault) {
        String profiles = String.join(", ", activeProfiles.names());
        return List.of(
                "profiles: " + profiles + (activeProfiles.names().equals(List.of("default"))
                        ? " — 'default' means NO profile is active; it is not a name @Profile matches"
                        : " — set by SPRING_PROFILES_ACTIVE (compose sets it to dev)"),
                "the safe values above were typed and validated at boot: AppConfig's constructor "
                        + "normalized guide.env.* — a bad value would have failed startup, "
                        + "not the first request",
                "guide.env.demo-api-key is classified secret — its value has no path into this "
                        + "answer; the inspector reports only whether it is the known DEV DEFAULT: "
                        + (secretIsDevDefault ? "yes (demo material — production sets the env var)"
                                : "no (a real value is set and stays unseen)"),
                "the flags are RUNTIME-switchable — config is not; data-mode is currently "
                        + mode.wireName() + " and PATCH /api/demo/scenario flips it live");
    }
}
