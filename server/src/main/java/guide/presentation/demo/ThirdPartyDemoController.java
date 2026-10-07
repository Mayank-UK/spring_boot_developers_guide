package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.thirdparty.Posture;
import guide.domain.thirdparty.ThirdPartyHub;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 43 (third-party) — the integration HUB. Each
 * trigger runs ONE integration through its fake and answers with the
 * receipt plus the integration's updated row (posture, last outcome,
 * quota state, key verdict); the dials flip quota and posture; the
 * report endpoint serves the whole hub. Deterministic and offline by
 * construction: no endpoint here can reach a network or echo a key value.
 *
 * - GET  /api/demo/thirdparty/hub → the hub report (all three rows + reading)
 * - POST /api/demo/thirdparty/mail?to=&subject= → one send through the fake
 * - POST /api/demo/thirdparty/payments?token=&amountCents=&currency=&description=
 * - POST /api/demo/thirdparty/storage?key=&sizeBytes=
 * - POST /api/demo/thirdparty/quota?integration=&limit= → the blast-radius dial
 * - POST /api/demo/thirdparty/posture?integration=&posture=fake|sandbox
 * - POST /api/demo/thirdparty/reset → constructed state (tour + test determinism)
 *
 * Thin as always: parse and validate at the edge, one hub call, typed 400
 * envelopes for bad input. Provider-shaped answers (declines, deferrals,
 * quota rejections) are VALUES on 200s — the provider answered; only bad
 * input is an error.
 */
@RestController
public class ThirdPartyDemoController {

    private final ThirdPartyHub hub;

    public ThirdPartyDemoController(ThirdPartyHub hub) {
        this.hub = hub;
    }

    /** GET /api/demo/thirdparty/hub → the whole hub: rows + reading lines. */
    @GetMapping(value = "/api/demo/thirdparty/hub", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.HubReport hub() {
        return hub.report();
    }

    /** POST /api/demo/thirdparty/mail?to=friend@example.com&subject=Welcome → one send. */
    @PostMapping(value = "/api/demo/thirdparty/mail", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.MailAnswer mail(
            @RequestParam(name = "to", defaultValue = "friend@example.com") String to,
            @RequestParam(name = "subject", defaultValue = "Welcome to the guide") String subject) {
        return hub.sendMail(to, subject, "Hi — this mail never left the process (the fake is the lesson).");
    }

    /** POST /api/demo/thirdparty/payments?token=tok_visa&amountCents=1999 → one charge. */
    @PostMapping(value = "/api/demo/thirdparty/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.ChargeAnswer payments(
            @RequestParam(name = "token", defaultValue = "tok_visa") String token,
            @RequestParam(name = "amountCents", defaultValue = "1999") long amountCents,
            @RequestParam(name = "currency", defaultValue = "usd") String currency,
            @RequestParam(name = "description", defaultValue = "guide demo charge") String description) {
        requireInRange(amountCents, 1, 999_999, "amountCents");
        requireLength(currency, 3, "currency");
        return hub.charge(token, amountCents, currency, description);
    }

    /** POST /api/demo/thirdparty/storage?key=guide/hello.txt&sizeBytes=2048 → one put. */
    @PostMapping(value = "/api/demo/thirdparty/storage", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.PutAnswer storage(
            @RequestParam(name = "key", defaultValue = "guide/hello.txt") String key,
            @RequestParam(name = "sizeBytes", defaultValue = "2048") int sizeBytes) {
        requireInRange(sizeBytes, 1, 1_048_576, "sizeBytes");
        return hub.putObject(key, sizeBytes);
    }

    /** POST /api/demo/thirdparty/quota?integration=mail&limit=1 → the blast-radius dial. */
    @PostMapping(value = "/api/demo/thirdparty/quota", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.DialAnswer quota(
            @RequestParam(name = "integration") String integration,
            @RequestParam(name = "limit") int limit) {
        requireKnownIntegration(integration);
        requireInRange(limit, ThirdPartyHub.MIN_QUOTA, ThirdPartyHub.MAX_QUOTA, "limit");
        return hub.setQuota(integration, limit);
    }

    /** POST /api/demo/thirdparty/posture?integration=payments&posture=sandbox → flip. */
    @PostMapping(value = "/api/demo/thirdparty/posture", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.DialAnswer posture(
            @RequestParam(name = "integration") String integration,
            @RequestParam(name = "posture") String posture) {
        requireKnownIntegration(integration);
        Posture parsed = Posture.fromWireName(posture);
        if (parsed == null || parsed == Posture.LIVE) {
            // the edge offers only what the demo can do: live is a key-and-wiring
            // decision, and the adapters refuse it at runtime anyway (defense deep)
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "posture must be one of fake, sandbox",
                    Map.of("parameter", "posture", "allowed", "fake, sandbox")));
        }
        return hub.setPosture(integration, parsed);
    }

    /** POST /api/demo/thirdparty/reset → constructed state (quota, counters, adapters). */
    @PostMapping(value = "/api/demo/thirdparty/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    ThirdPartyHub.HubReport reset() {
        hub.reset();
        return hub.report();
    }

    private void requireKnownIntegration(String integration) {
        if (!hub.integrationIds().contains(integration)) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "integration must be one of " + String.join(", ", hub.integrationIds()),
                    Map.of("parameter", "integration", "allowed", hub.integrationIds())));
        }
    }

    private static void requireInRange(long value, long min, long max, String parameter) {
        if (value < min || value > max) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    parameter + " must be between " + min + " and " + max,
                    Map.of("parameter", parameter, "min", min, "max", max)));
        }
    }

    private static void requireLength(String value, int length, String parameter) {
        if (value == null || value.length() != length) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    parameter + " must be exactly " + length + " characters (an ISO currency code)",
                    Map.of("parameter", parameter)));
        }
    }
}
