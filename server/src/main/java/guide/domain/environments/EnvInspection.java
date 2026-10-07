package guide.domain.environments;

import java.util.List;

/**
 * The env inspector's answer (topic 36, demo {@code env:inspector}): the
 * environment story for a CONTROLLED, teachable set of facts — never a dump
 * of the whole Environment.
 *
 * - activeProfiles — which Spring profiles this process runs under;
 * - properties — the curated {@code guide.env.*} values from the typed
 *   AppConfig record, each classified SAFE (echoed) or SECRET (redacted:
 *   the value NEVER travels, only its shape does);
 * - demoSecretIsDevDefault — the safe verdict on the secret key: it leaks
 *   nothing about a real value while surfacing "prod forgot the env var";
 * - flags — the RUNTIME-switchable toggles (the ScenarioStore), reported
 *   beside the config on purpose: same screen, different rails;
 * - reading — the narration lines that make the report self-teaching.
 */
public record EnvInspection(
        List<String> activeProfiles,
        List<PropertyLine> properties,
        boolean demoSecretIsDevDefault,
        List<FlagLine> flags,
        List<String> reading) {

    /** One config value as reported: the key, the (possibly redacted) value, its classification. */
    public record PropertyLine(String key, String value, String classification) {
    }

    /** One runtime toggle: name, current state, whether it flips without a restart, and the how. */
    public record FlagLine(String name, String state, boolean runtimeSwitchable, String note) {
    }
}
