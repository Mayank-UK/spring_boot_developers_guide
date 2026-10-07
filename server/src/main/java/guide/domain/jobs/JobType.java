package guide.domain.jobs;

import java.util.Arrays;
import java.util.Optional;

/**
 * The work this guide queues. A closed enum on purpose: a job type is a
 * CONTRACT between the enqueuer and a handler registered at boot — an open
 * string type would let any caller invent work nobody executes. Adding a
 * kind of background work means adding the handler beside it (the composition
 * root refuses a duplicate handler for one type), so the enum and the
 * handler registry can never drift apart silently.
 *
 * The three members are deliberately archetypal:
 * - send-welcome-email — the canonical job (external, slow, flaky,
 *   non-critical — see the topic's email section);
 * - generate-report — the heavy CPU/IO job that must leave the request
 *   path entirely;
 * - cleanup-pii — the recurring maintenance sweep (a cron-shaped job
 *   enqueued on a schedule).
 */
public enum JobType {
    SEND_WELCOME_EMAIL("send-welcome-email"),
    GENERATE_REPORT("generate-report"),
    CLEANUP_PII("cleanup-pii");

    private final String wireName;

    JobType(String wireName) {
        this.wireName = wireName;
    }

    /** Serialized on the wire as the lower-case hyphenated form the console speaks. */
    public String wireName() {
        return wireName;
    }

    /** Parse the console/curl vocabulary; empty on an unknown name (callers turn that into validation). */
    public static Optional<JobType> fromWireName(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(raw.trim()))
                .findFirst();
    }

    /** The comma-separated list every validation message quotes — one source of truth. */
    public static String wireNames() {
        StringBuilder names = new StringBuilder();
        for (JobType type : values()) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(type.wireName());
        }
        return names.toString();
    }
}
