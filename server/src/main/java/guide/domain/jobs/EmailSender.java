package guide.domain.jobs;

/**
 * The port for the guide's canonical slow external dependency. Email is the
 * teaching job because it is the perfect shape: external (an SMTP relay you
 * do not operate), slow (hundreds of milliseconds to seconds), flaky (grey
 * listing, 4xx deferrals, provider incidents) and non-critical (nobody's
 * checkout blocks on a welcome mail) — every reason work moves to the
 * background, embodied in one dependency.
 *
 * The production adapter for this guide is the in-memory recorder
 * (data/jobs) — the demo must not need a real SMTP account to teach retries.
 * A real adapter wraps Spring Mail's JavaMailSender (topic 43 wires one);
 * it should throw on transport failure so the queue's retry machinery sees
 * the flakiness, exactly like the recording fake's scripted failures.
 */
public interface EmailSender {

    /**
     * Send one message; THROW on transport failure — the attempt's error is
     * born here. Must tolerate re-sends: a retry after an ambiguous
     * failure may double-send, which is why welcome mails carry no
     * side effects a duplicate would break.
     */
    void send(String to, String subject, String body);
}
