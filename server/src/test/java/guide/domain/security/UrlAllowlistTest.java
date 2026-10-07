package guide.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The SSRF guard's judgment table, pinned: public destinations pass, the
 * explicitly-allowlisted loopback receiver passes, and every flavor of
 * "place our HTTP client must never be aimed at" — metadata service
 * (IP and names), loopback, link-local, private ranges, the unspecified
 * address, and non-HTTP schemes — is refused with a reason that names the
 * class of target.
 */
class UrlAllowlistTest {

    private final UrlAllowlist devPolicy = UrlAllowlist.forLocalDevelopment();
    private final UrlAllowlist strictPolicy = new UrlAllowlist(Set.of());

    @Test
    void publicHttpAndHttpsDestinationsAreAllowed() {
        assertTrue(devPolicy.check("https://example.com/webhooks").allowed());
        assertTrue(devPolicy.check("http://203.0.113.7:9090/hook").allowed());
        assertEquals("destination is on the outbound allowlist",
                devPolicy.check("https://example.com").reason());
    }

    @Test
    void theExplicitlyAllowlistedLoopbackReceiverPassesTheDevelopmentPolicy() {
        assertTrue(devPolicy.check("http://localhost:8080/api/demo/webhooks/receiver").allowed());
        assertTrue(devPolicy.check("http://127.0.0.1:8080/receiver").allowed());
    }

    @Test
    void loopbackIsRefusedWithoutANamedException() {
        UrlAllowlist.Verdict refused = strictPolicy.check("http://127.0.0.1:8080/debug");
        assertFalse(refused.allowed());
        assertTrue(refused.reason().contains("loopback"));
    }

    @Test
    void theCloudMetadataIpAddressIsRefused() {
        UrlAllowlist.Verdict refused = strictPolicy.check("http://169.254.169.254/latest/meta-data/");
        assertFalse(refused.allowed());
        assertTrue(refused.reason().contains("link-local"),
                "the metadata IP is link-local — the reason must say so");
    }

    @Test
    void metadataHostAliasesAreRefusedByName() {
        assertFalse(strictPolicy.check("http://metadata/latest/meta-data/").allowed());
        UrlAllowlist.Verdict refused = strictPolicy.check("http://metadata.google.internal/computeMetadata/v1/");
        assertFalse(refused.allowed());
        assertTrue(refused.reason().contains("metadata"));
    }

    @Test
    void privateRangesAreRefusedWithTheRangeNamed() {
        for (String url : new String[] {
                "http://10.0.0.5/admin", "http://192.168.1.10/router", "http://172.16.0.1/config"}) {
            UrlAllowlist.Verdict refused = strictPolicy.check(url);
            assertFalse(refused.allowed(), url + " must be refused");
            assertTrue(refused.reason().contains("private"), refused.reason());
        }
    }

    @Test
    void ipv6LoopbackAndUnspecifiedAreRefused() {
        assertFalse(strictPolicy.check("http://[::1]:9090/").allowed());
        UrlAllowlist.Verdict unspecified = strictPolicy.check("http://[::]:8080/");
        assertFalse(unspecified.allowed());
    }

    @Test
    void nonHttpSchemesNeverReachTheHostJudgment() {
        UrlAllowlist.Verdict refused = strictPolicy.check("file:///etc/passwd");
        assertFalse(refused.allowed());
        assertTrue(refused.reason().contains("scheme"));
    }

    @Test
    void garbageInputIsARefusalNotAThrow() {
        assertFalse(strictPolicy.check(null).allowed());
        assertFalse(strictPolicy.check("   ").allowed());
        assertFalse(strictPolicy.check("https://").allowed());
    }
}
