package guide.domain.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints this app's own HS256 JWTs — the "authorization server half" of the
 * demo (validation is the resource server's job, driven by the yaml
 * secret). Claims are deliberately minimal and standard: sub (the login
 * name), roles (topic 24's authorization material), iat, exp (SHORT —
 * 15 minutes by default, the whole reason refresh tokens exist), and jti —
 * a unique id per token, the only handle a revocation list could hold.
 *
 * HS256 means ONE secret both signs and verifies: right for a single
 * service that is its own issuer, wrong the moment a second service must
 * verify tokens it did not mint (then RS256/ES256 — the resource-server
 * section walks that trade-off).
 */
public final class JwtMinter {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final byte[] secretHmacKey;
    private final Clock clock;

    public JwtMinter(String secret, Clock clock) {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            // HS256 needs >= 256 bits of key — nimbus refuses shorter at sign time anyway
            throw new IllegalArgumentException("HS256 secret must be at least 32 bytes");
        }
        this.secretHmacKey = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** A freshly signed access token, plus the decoded-claims preview the demo shows. */
    public MintedAccessToken mint(UserPrincipal user, Duration ttl) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user.username())
                    .claim("roles", user.roles())
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
            jwt.sign(new MACSigner(secretHmacKey));
            return new MintedAccessToken(jwt.serialize(), claimsPreview(claims), now, expiresAt);
        } catch (JOSEException signingFailure) {
            throw new IllegalStateException("JWT signing failed", signingFailure);
        }
    }

    /** What the demo surfaces after minting — the claims any client CAN decode (no trust, just preview). */
    private static Map<String, Object> claimsPreview(JWTClaimsSet claims) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("sub", claims.getSubject());
        preview.put("roles", claims.getClaim("roles"));
        preview.put("jti", claims.getJWTID());
        preview.put("iat", claims.getIssueTime().toInstant().toString());
        preview.put("exp", claims.getExpirationTime().toInstant().toString());
        return preview;
    }

    public record MintedAccessToken(String token, Map<String, Object> claims,
            Instant issuedAt, Instant expiresAt) {
    }
}
