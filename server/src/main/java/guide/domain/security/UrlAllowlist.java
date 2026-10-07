package guide.domain.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * The SSRF guard (topic 25): default-DENY for outbound destinations, with an
 * explicit allowlist for the few hosts this app may legitimately call. A
 * webhook subscription URL is the specimen — the SERVER chooses to fetch it
 * later, so a hostile subscriber can aim our own HTTP client at places it
 * should never go: the cloud metadata service (169.254.169.254, the address
 * that hands out instance credentials), loopback admin ports, or private
 * network ranges. That is Server-Side Request Forgery: the attacker's URL,
 * our network position.
 *
 * The judgment order is the teaching point:
 * 1. scheme must be http/https (never file:, never gopher-style surprises);
 * 2. a host EXPLICITLY on the allowlist passes (the app's own loopback
 *    receiver for the webhooks demo — a named exception, not a range);
 * 3. any host that parses as an IP literal is checked against the address
 *    classes SSRF wants: loopback (127/8, ::1), link-local (169.254/10,
 *    fe80/10 — the metadata service lives here), site-local/private
 *    (10/8, 172.16/12, 192.168/16, fc00/7), and "any" (0.0.0.0);
 * 4. the metadata NAMES are refused outright (metadata, metadata.*) — the
 *    aliases cloud providers document for the same service.
 *
 * Honest limits, on purpose: hostnames are NOT resolved to IPs before the
 * verdict. Resolving introduces a TOCTOU race (DNS rebinding — first lookup
 * public, second lookup 169.254.169.254) and a boot-time network dependency
 * for a validation step; production systems close this with an egress proxy
 * or a resolver that pins the first answer. The javadoc says so because a
 * guard that pretends completeness is worse than none.
 */
public final class UrlAllowlist {

    /** The verdict plus, on refusal, the human-readable reason (teaching surface). */
    public record Verdict(boolean allowed, String reason) {

        static final Verdict ALLOWED = new Verdict(true, "destination is on the outbound allowlist");

        public static Verdict refused(String reason) {
            return new Verdict(false, reason);
        }
    }

    private final Set<String> permittedHosts;

    /**
     * @param permittedHosts lowercase hostnames/IP literals explicitly allowed
     *         to bypass the private-range denials (the loopback receiver)
     */
    public UrlAllowlist(Set<String> permittedHosts) {
        this.permittedHosts = Set.copyOf(permittedHosts);
    }

    /** The webhooks demo's policy: public destinations plus the app's own receiver. */
    public static UrlAllowlist forLocalDevelopment() {
        return new UrlAllowlist(Set.of("localhost", "127.0.0.1", "[::1]", "::1"));
    }

    /** Judge one outbound destination URL. */
    public Verdict check(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (IllegalArgumentException badUri) {
            return Verdict.refused("url is not a valid URI: " + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Verdict.refused("scheme must be http or https — outbound fetches only speak HTTP(S)");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Verdict.refused("url has no host to judge");
        }
        // URI keeps IPv6 literals bracketed ("[::1]") — strip for the checks below
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (permittedHosts.contains(normalized)) {
            return Verdict.ALLOWED;
        }
        if (isMetadataName(normalized)) {
            return Verdict.refused("'" + normalized + "' is a cloud metadata service alias — "
                    + "the credential endpoint SSRF exists to reach");
        }
        InetAddress address = parseAddress(normalized);
        if (address != null) {
            if (address.isLoopbackAddress()) {
                return Verdict.refused(host + " is loopback — the app's own machine is not a webhook destination "
                        + "(allowlist named hosts if you mean the local receiver)");
            }
            if (address.isLinkLocalAddress()) {
                return Verdict.refused(host + " is link-local — this range hides the cloud metadata service "
                        + "(169.254.169.254 and friends)");
            }
            if (address.isSiteLocalAddress()) {
                return Verdict.refused(host + " is a private network address — the internal network is not "
                        + "a webhook destination");
            }
            if (address.isAnyLocalAddress()) {
                return Verdict.refused(host + " is the unspecified address (0.0.0.0/::)");
            }
        }
        return Verdict.ALLOWED;
    }

    private static boolean isMetadataName(String host) {
        return host.equals("metadata") || host.startsWith("metadata.");
    }

    /**
     * IP-literal parse only — {@link InetAddress#ofLiteral(String)} answers
     * literals without a DNS round trip (and signals a non-literal with an
     * unchecked exception, no network call), so hostnames are never resolved
     * here (see class docs: no TOCTOU window, no validation-time network I/O).
     */
    private static InetAddress parseAddress(String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException notAnIpLiteral) {
            return null;
        }
    }
}
