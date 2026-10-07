package guide.domain.thirdparty;

import java.util.List;

/**
 * Key HANDLING for the hub — verdicts only, never values. This is topic
 * 36's redaction discipline applied to integrations: an operational
 * surface may report WHETHER a credential is present (that is the fact
 * on-call needs — "production forgot to set the key"), but a key VALUE
 * has no code path into any answer, because there is no question worth
 * asking that the value answers.
 *
 * The in-process fakes this guide runs need NO credential at all — there
 * is nothing to leak by construction. When an env var IS set (say, in a
 * real deployment), the verdict flips to "present — and still never
 * echoed"; the value itself is read by the LIVE adapter's wiring at boot,
 * not by anything that serves requests.
 *
 * Values arrive as PLAIN booleans from the composition root, which reads
 * the Spring Environment — the domain stays Spring-free (topic 32's rule).
 */
public record ThirdPartyKeys(List<KeyState> integrations) {

    /** One integration's key state: the env var NAME (public knowledge), present-from-env, verdict. */
    public record KeyState(String integration, String envVar, boolean presentFromEnv) {

        public String verdict() {
            return presentFromEnv
                    ? envVar + " is set — present, and its value has NO code path into any answer"
                    : envVar + " is not set — nothing to leak: the in-process fake needs no credential";
        }
    }

    public static ThirdPartyKeys of(KeyState... states) {
        return new ThirdPartyKeys(List.of(states));
    }

    /** The key state for one integration id; null when unknown — the edge validates. */
    public KeyState forIntegration(String integrationId) {
        return integrations.stream()
                .filter(state -> state.integration().equals(integrationId))
                .findFirst()
                .orElse(null);
    }
}
