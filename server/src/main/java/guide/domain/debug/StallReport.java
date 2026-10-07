package guide.domain.debug;

import java.util.List;

/**
 * The stall demo's answer: whether a stall is parked right now, which kind
 * ("lock" or "spin"), when it started and how long the previous one was
 * parked before it was stopped, how many threads the whole JVM is running
 * (the honest size of a real dump), the theater's own threads as a dump
 * would show them, and READING lines — the teaching narration that says
 * what each observed state means. The reading is derived from what the
 * threads actually report, never asserted from what was requested: if the
 * victim never reached BLOCKED, the reading does not claim it did.
 */
public record StallReport(boolean running, String kind, long startedAtMs, long lastParkedMs,
        int jvmThreadCount, List<StallThreadView> threads, List<String> reading) {

    public StallReport {
        kind = kind == null ? "" : kind;
        threads = threads == null ? List.of() : List.copyOf(threads);
        reading = reading == null ? List.of() : List.copyOf(reading);
    }
}
