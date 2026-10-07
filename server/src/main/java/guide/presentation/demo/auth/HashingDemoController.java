package guide.presentation.demo.auth;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.auth.PasswordHashLab;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 23 (authentication), the PasswordEncoder lab:
 * - POST /api/demo/auth/hashing/hash    {password, cost?} → a real bcrypt
 *   hash at the chosen cost + how long minting took
 * - POST /api/demo/auth/hashing/verify  {password, hash} → matches? + how
 *   long the check took — correct and wrong passwords cost the same
 * - GET  /api/demo/auth/hashing/compare?hash=… → the format dissected:
 *   $2a$10$ + 22-char salt + 31-char hash body, annotated
 */
@RestController
public class HashingDemoController {

    private final PasswordHashLab lab;

    public HashingDemoController(PasswordHashLab lab) {
        this.lab = lab;
    }

    public record HashRequest(String password, Integer cost) {
    }

    public record VerifyRequest(String password, String hash) {
    }

    @PostMapping(value = "/api/demo/auth/hashing/hash",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> hash(@RequestBody HashRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw new AppException(Failure.of(FailureKind.VALIDATION, "password is required"));
        }
        PasswordHashLab.HashedPassword hashed = lab.hash(request.password(), request.cost());
        return Map.of(
                "hash", hashed.hash(),
                "format", hashed.format(),
                "cost", hashed.cost(),
                "elapsedMs", roundedMs(hashed.elapsedNanos()));
    }

    @PostMapping(value = "/api/demo/auth/hashing/verify",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> verify(@RequestBody VerifyRequest request) {
        if (request.password() == null || request.hash() == null) {
            throw new AppException(Failure.of(FailureKind.VALIDATION,
                    "password and hash are both required"));
        }
        PasswordHashLab.Verification verification;
        try {
            verification = lab.verify(request.password(), request.hash());
        } catch (IllegalArgumentException notBcrypt) {
            throw new AppException(Failure.of(FailureKind.VALIDATION, notBcrypt.getMessage()));
        }
        return Map.of(
                "matches", verification.matches(),
                "elapsedMs", roundedMs(verification.elapsedNanos()));
    }

    @GetMapping(value = "/api/demo/auth/hashing/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> compare(@RequestParam(name = "hash") String hash) {
        if (hash == null || hash.isBlank()) {
            throw new AppException(Failure.of(FailureKind.VALIDATION,
                    "hash query parameter is required"));
        }
        PasswordHashLab.HashAnatomy anatomy;
        try {
            anatomy = lab.dissect(hash);
        } catch (IllegalArgumentException notBcrypt) {
            throw new AppException(Failure.of(FailureKind.VALIDATION, notBcrypt.getMessage()));
        }
        return Map.of(
                "format", anatomy.format(),
                "version", anatomy.version(),
                "cost", anatomy.cost(),
                "salt", anatomy.salt(),
                "hashBody", anatomy.hashBody(),
                "notes", List.copyOf(anatomy.notes()));
    }

    private static double roundedMs(long nanos) {
        return Math.round(nanos / 100_000.0) / 10.0;
    }
}
