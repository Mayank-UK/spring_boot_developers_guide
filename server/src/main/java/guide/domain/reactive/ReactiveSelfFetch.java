package guide.domain.reactive;

import java.util.List;

/**
 * The reactive:compare demo's client half (topic 41) — a reactive HTTP
 * fetch of this app's own ndjson stream, behind a plain domain port so the
 * WebClient machinery stays in the data layer (the same port/adapter split
 * every area of this guide uses). The report is what the demo teaches:
 * what was fetched, which THREADS the pipeline ran on (the reactive
 * client's event loops — visible proof the wait was not a servlet thread),
 * how long the round trip took, and whether the answer came from a real
 * socket (live mode) or the deterministic stubbed exchange (fake mode —
 * the default, so tests never open a port).
 */
public interface ReactiveSelfFetch {

    /** One ndjson element the stream endpoint emits. */
    record StreamItem(int seq, String payload) {
    }

    /** The measured fetch: mode, counts, observed threads, wall time, reading. */
    record FetchReport(
            String mode,
            String url,
            int requested,
            int fetched,
            List<String> threads,
            long wallNanos,
            List<String> reading) {
    }

    /** Fetches {@code items} elements of the stream (delayed {@code delayMillis} each in live mode). */
    FetchReport fetch(int items, long delayMillis);
}
