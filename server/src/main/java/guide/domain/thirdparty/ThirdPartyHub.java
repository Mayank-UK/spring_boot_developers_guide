package guide.domain.thirdparty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The third-party hub — one use case wrapping every integration the topic
 * teaches (mail, payments, storage) in the operational frame an on-call
 * engineer actually wants: POSTURE (fake/sandbox — how the adapter is
 * answering), the LAST operation's outcome (sent / queued / declined /
 * stored / failed / rate-limited), QUOTA state (a deterministic counter
 * with a dial — rate-limited after N calls), and KEY HANDLING (verdicts
 * only, never values).
 *
 * BLAST RADIUS is the design's whole point: quota, posture and counters
 * are PER-INTEGRATION, so one degraded provider (quota exhausted, calls
 * failing) never degrades its siblings — the report makes the containment
 * visible, and the demo's quota dial proves it live.
 *
 * A plain synchronized bean by design (the ScenarioStore precedent): the
 * demo flips state at runtime through the controller; there is no
 * persistence and no contention at teaching scale.
 */
public final class ThirdPartyHub {

    /** The per-integration call budget before the quota gate rejects. */
    public static final int DEFAULT_QUOTA = 8;

    /** Bounds the controller enforces on the quota dial (0 = already exhausted). */
    public static final int MIN_QUOTA = 0;
    public static final int MAX_QUOTA = 64;

    private final Map<String, IntegrationPort> ports;
    private final ThirdPartyKeys keys;
    private final Map<String, String> titles;
    private final Map<String, String> whats;
    private final Map<String, String> wiringNotes;
    private final Map<String, Integer> quotaLimits = new LinkedHashMap<>();
    private final Map<String, Integer> callsUsed = new LinkedHashMap<>();
    private final Map<String, LastOperation> lastOperations = new LinkedHashMap<>();

    /** What one integration last did — the hub's operational memory. */
    public record LastOperation(String operation, String outcome, String detail) {
    }

    /** One integration's row in the hub report. */
    public record IntegrationView(String id, String title, String what, String posture,
            String lastOperation, String lastOutcome, String lastDetail,
            int callsUsed, int quotaLimit, String quotaState,
            String keyEnvVar, boolean keyPresentFromEnv, String keyVerdict,
            List<String> notes) {
    }

    /** The whole-hub answer: one row per integration plus self-explaining reading lines. */
    public record HubReport(List<IntegrationView> integrations, List<String> reading) {
    }

    /** A mail trigger's answer: the receipt plus the integration row it produced. */
    public record MailAnswer(String outcome, String messageId, String detail,
            IntegrationView after) {
    }

    /** A charge trigger's answer. */
    public record ChargeAnswer(String outcome, String chargeId, String declineCode,
            boolean testMode, String detail, IntegrationView after) {
    }

    /** A put trigger's answer. */
    public record PutAnswer(String outcome, String key, String etag, int bytes,
            String detail, IntegrationView after) {
    }

    /** The dial's answer: what changed, and the row that proves it. */
    public record DialAnswer(String changed, IntegrationView after) {
    }

    public ThirdPartyHub(MailPort mail, PaymentPort payments, StoragePort storage,
            ThirdPartyKeys keys, Map<String, String> notes) {
        this.ports = new LinkedHashMap<>();
        this.ports.put("mail", Objects.requireNonNull(mail, "mail"));
        this.ports.put("payments", Objects.requireNonNull(payments, "payments"));
        this.ports.put("storage", Objects.requireNonNull(storage, "storage"));
        this.keys = Objects.requireNonNull(keys, "keys");
        this.titles = Map.of(
                "mail", "Spring Mail",
                "payments", "Payments (Stripe-shaped)",
                "storage", "Object storage (S3-shaped)");
        this.whats = Map.of(
                "mail", "transactional email behind a port — fake, sandbox-greylisting, or a real relay",
                "payments", "charges with provider test vectors — declines are answers, not outages",
                "storage", "keyed objects with provider discipline — validated keys, bounded store");
        this.wiringNotes = Map.copyOf(Objects.requireNonNull(notes, "notes"));
        for (String id : List.of("mail", "payments", "storage")) {
            quotaLimits.put(id, DEFAULT_QUOTA);
            callsUsed.put(id, 0);
        }
    }

    /** The integration ids the hub knows — the controller's validation vocabulary. */
    public List<String> integrationIds() {
        return List.copyOf(ports.keySet());
    }

    public synchronized HubReport report() {
        List<IntegrationView> rows = ports.keySet().stream().map(this::view).toList();
        return new HubReport(rows, reading());
    }

    /** Send one mail through the mail port — after the quota gate. */
    public synchronized MailAnswer sendMail(String to, String subject, String body) {
        MailPort port = (MailPort) ports.get("mail");
        if (gateExhausted("mail")) {
            return new MailAnswer("rate_limited", null,
                    "quota gate: " + callsUsed.get("mail") + "/" + quotaLimits.get("mail")
                            + " calls used — the provider call was never made",
                    view("mail"));
        }
        MailPort.MailReceipt receipt;
        try {
            receipt = port.send(to, subject, body);
        } catch (RuntimeException failure) {
            record("mail", "send", "failed", failure.getMessage());
            throw failure;
        }
        record("mail", "send", receipt.outcome(), receipt.detail());
        return new MailAnswer(receipt.outcome(), receipt.messageId(), receipt.detail(),
                view("mail"));
    }

    /** Charge one token through the payments port — after the quota gate. */
    public synchronized ChargeAnswer charge(String token, long amountCents, String currency,
            String description) {
        PaymentPort port = (PaymentPort) ports.get("payments");
        if (gateExhausted("payments")) {
            return new ChargeAnswer("rate_limited", null, null, false,
                    "quota gate: " + callsUsed.get("payments") + "/" + quotaLimits.get("payments")
                            + " calls used — the provider call was never made",
                    view("payments"));
        }
        PaymentPort.ChargeReceipt receipt;
        try {
            receipt = port.charge(token, amountCents, currency, description);
        } catch (RuntimeException failure) {
            record("payments", "charge", "failed", failure.getMessage());
            throw failure;
        }
        record("payments", "charge", receipt.outcome(), receipt.detail());
        return new ChargeAnswer(receipt.outcome(), receipt.chargeId(), receipt.declineCode(),
                receipt.testMode(), receipt.detail(), view("payments"));
    }

    /** Put one object through the storage port — after the quota gate. */
    public synchronized PutAnswer putObject(String key, int sizeBytes) {
        StoragePort port = (StoragePort) ports.get("storage");
        if (gateExhausted("storage")) {
            return new PutAnswer("rate_limited", key, null, 0,
                    "quota gate: " + callsUsed.get("storage") + "/" + quotaLimits.get("storage")
                            + " calls used — the provider call was never made",
                    view("storage"));
        }
        StoragePort.StoredReceipt receipt;
        try {
            receipt = port.put(key, sizeBytes);
        } catch (RuntimeException failure) {
            record("storage", "put", "failed", failure.getMessage());
            throw failure;
        }
        record("storage", "put", receipt.outcome(), receipt.detail());
        return new PutAnswer(receipt.outcome(), receipt.key(), receipt.etag(), receipt.bytes(),
                receipt.detail(), view("storage"));
    }

    /** The quota dial — one integration's call budget, without touching the others. */
    public synchronized DialAnswer setQuota(String integrationId, int limit) {
        requireKnown(integrationId);
        quotaLimits.put(integrationId, limit);
        return new DialAnswer("quota for " + integrationId + " is now " + limit
                + " call" + (limit == 1 ? "" : "s") + " (used " + callsUsed.get(integrationId) + ")",
                view(integrationId));
    }

    /** The posture dial — fake adapters flip; real adapters refuse (live is wiring, not a dial). */
    public synchronized DialAnswer setPosture(String integrationId, Posture posture) {
        requireKnown(integrationId);
        IntegrationPort port = ports.get(integrationId);
        if (!port.adoptPosture(posture)) {
            return new DialAnswer(integrationId + " runs " + port.posture().wireName()
                    + " and refuses runtime posture changes — live is a key-and-wiring decision",
                    view(integrationId));
        }
        return new DialAnswer(integrationId + " now answers in " + posture.wireName(),
                view(integrationId));
    }

    /** Back to constructed state: quotas, counters, outcomes, and every adapter. */
    public synchronized void reset() {
        for (String id : ports.keySet()) {
            quotaLimits.put(id, DEFAULT_QUOTA);
            callsUsed.put(id, 0);
            lastOperations.put(id, null);
            ports.get(id).reset();
        }
    }

    // ---- the machinery ----------------------------------------------------

    /**
     * The quota gate: true when the budget is spent. Note what it does NOT
     * do — touch any other integration. That isolation IS the blast-radius
     * lesson: mail can be rate-limited to death while payments never
     * noticed.
     */
    private boolean gateExhausted(String id) {
        return callsUsed.get(id) >= quotaLimits.get(id);
    }

    private void record(String id, String operation, String outcome, String detail) {
        callsUsed.merge(id, 1, Integer::sum);
        lastOperations.put(id, new LastOperation(operation, outcome, detail));
    }

    private IntegrationView view(String id) {
        IntegrationPort port = ports.get(id);
        ThirdPartyKeys.KeyState key = keys.forIntegration(id);
        LastOperation last = lastOperations.get(id);
        int used = callsUsed.get(id);
        int limit = quotaLimits.get(id);
        String note = wiringNotes.get(id);
        return new IntegrationView(id, titles.get(id), whats.get(id),
                port.posture().wireName(),
                last == null ? null : last.operation(),
                last == null ? "idle" : last.outcome(),
                last == null ? null : last.detail(),
                used, limit,
                used >= limit ? "exhausted" : "healthy",
                key == null ? null : key.envVar(),
                key != null && key.presentFromEnv(),
                key == null ? null : key.verdict(),
                note == null ? List.of() : List.of(note));
    }

    private List<String> reading() {
        List<String> lines = new ArrayList<>();
        lines.add("posture per integration: fake = in-process adapter (no provider, no key, "
                + "no network); sandbox = the provider's TEST semantics, still in-process — "
                + "POST /api/demo/thirdparty/posture flips either");
        lines.add("quota is PER-INTEGRATION state: dial one down (POST /quota), exhaust it, "
                + "and its siblings keep answering — blast radius contained by construction");
        lines.add("key handling: verdicts only, never values — a set key reads \"present\", an "
                + "unset one reads \"nothing to leak\"; no endpoint in this app can echo a "
                + "credential");
        lines.add("every trigger on this hub is deterministic and offline: the fakes are the "
                + "lesson, and the one LIVE-capable adapter (mail over JavaMailSender) is "
                + "constructed by the config but never called by the demo");
        return lines;
    }

    private void requireKnown(String id) {
        if (!ports.containsKey(id)) {
            throw new IllegalArgumentException("unknown integration: " + id);
        }
    }
}
