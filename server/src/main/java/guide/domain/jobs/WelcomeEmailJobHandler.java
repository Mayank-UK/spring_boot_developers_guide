package guide.domain.jobs;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Handler for send-welcome-email — the canonical job. The work: read the
 * recipient out of the payload, render a one-line template, hand it to the
 * {@link EmailSender} port. The demo's deterministic failure injection
 * lives here on purpose, ahead of the port call: attempts 1..N throw a
 * transport-shaped error ("SMTP 421 — try again later", the greylisting
 * answer real relays give), attempt N+1 sends for real — so a full retry
 * episode (fail, backoff, fail, succeed) reproduces in a curl tour without
 * owning a flaky SMTP account. Production drops the injection and keeps
 * everything else byte for byte.
 *
 * Idempotency note: this handler is safe under at-least-once because a
 * duplicate welcome mail is harmless — no side effect a repeat breaks. A
 * billing mail would need a dedupe key (the messaging topic's consumer
 * discipline, cross-quoted in the topic prose).
 */
public final class WelcomeEmailJobHandler implements JobHandler {

    private final EmailSender emailSender;
    private final ObjectMapper mapper;

    public WelcomeEmailJobHandler(EmailSender emailSender, ObjectMapper mapper) {
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public JobType type() {
        return JobType.SEND_WELCOME_EMAIL;
    }

    @Override
    public void execute(JobRecord job) {
        int failedAlready = job.attempts().size();
        if (failedAlready < job.failFirstAttempts()) {
            throw new IllegalStateException("injected failure #" + (failedAlready + 1)
                    + " of " + job.failFirstAttempts() + " — SMTP 421, try again later");
        }
        JsonNode payload = mapper.readTree(job.payload());
        String to = payload.hasNonNull("to") ? payload.path("to").asString()
                : "friend@example.com";
        String name = payload.hasNonNull("name") ? payload.path("name").asString() : "friend";
        emailSender.send(to, "Welcome to the guide, " + name,
                "Hi " + name + " — your account is ready. (sent by job " + job.id() + ")");
    }
}
