package guide.domain.auth;

import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The PasswordEncoder laboratory: hash with a chosen bcrypt cost, verify
 * with timing evidence, dissect the hash format. The demo's central
 * measurement is the timing one: verifying the CORRECT password and a
 * WRONG password cost the SAME — bcrypt always runs its full schedule,
 * so an attacker cannot learn how many characters were right from the
 * clock (compare with the constant-time lesson from the webhooks topic:
 * same threat, different layer).
 */
public final class PasswordHashLab {

    public record HashedPassword(String hash, int cost, long elapsedNanos, String format) {
    }

    public record Verification(boolean matches, long elapsedNanos) {
    }

    public record HashAnatomy(String format, String version, int cost, String salt,
            String hashBody, List<String> notes) {
    }

    public HashedPassword hash(String password, Integer requestedCost) {
        int cost = clampCost(requestedCost);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(cost);
        long start = System.nanoTime();
        String hash = encoder.encode(password);
        long elapsed = System.nanoTime() - start;
        return new HashedPassword(hash, cost, elapsed, "bcrypt");
    }

    public Verification verify(String password, String hash) {
        if (hash == null || !hash.startsWith("$2")) {
            throw new IllegalArgumentException("not a bcrypt hash: expected the $2…$ format");
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        long start = System.nanoTime();
        boolean matches = encoder.matches(password, hash);
        long elapsed = System.nanoTime() - start;
        return new Verification(matches, elapsed);
    }

    /** Dissect $2a$10$<22-char salt><31-char hashBody> into its named parts (60 chars total). */
    public HashAnatomy dissect(String hash) {
        if (hash == null || hash.length() != 60 || hash.charAt(0) != '$') {
            throw new IllegalArgumentException(
                    "not a bcrypt hash: the format is $version$cost$22-char-salt + 31-char-hash (60 chars)");
        }
        String version = hash.substring(1, 3);
        int cost = Integer.parseInt(hash.substring(4, 6));
        return new HashAnatomy("bcrypt", version, cost,
                hash.substring(7, 29),
                hash.substring(29),
                List.of(
                        "version " + version + " — the algorithm revision (2a/2b/2y differ on edge cases)",
                        "cost " + cost + " — 2^cost bcrypt rounds; +1 doubles the work",
                        "salt — 22 base64 chars, generated per hash: same password, different hash every time",
                        "hash body — 31 chars, derived from password+salt; only this half is the secret"));
    }

    private static int clampCost(Integer requested) {
        int cost = requested == null ? 10 : requested;
        if (cost < 4 || cost > 15) {
            throw new IllegalArgumentException("bcrypt cost must be between 4 and 15");
        }
        return cost;
    }
}
