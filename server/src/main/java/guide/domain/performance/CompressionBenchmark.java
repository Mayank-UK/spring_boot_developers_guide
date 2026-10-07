package guide.domain.performance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The engine behind topic 38's perf:bench demo: a realistic few-KB JSON
 * payload, compressed and decompressed N times at TWO gzip levels, with the
 * numbers that teach reported honestly. The benchmark discipline this file
 * rehearses in miniature:
 * - WARM UP before timing (the JIT compiles the hot path on the first
 *   passes; timing pass 1 measures the compiler, not the code);
 * - report MEDIANS over many passes, never one lucky sample;
 * - assert what is deterministic (bytes in, bytes out — the ratio), never
 *   what is not (micros move run to run);
 * - the payload is real-shaped JSON — repeated keys, prose bodies — because
 *   gzip's ratio on degenerate input teaches the wrong lesson.
 *
 * Uses only the JDK's java.util.zip — the same deflate family the servlet
 * container calls when server.compression is on — so the demo adds no
 * dependency and measures the codec the wire would actually use.
 */
public final class CompressionBenchmark {

    /** Payload profiles the endpoint accepts; names are the whole UI. */
    public static final List<String> PAYLOAD_PROFILES = List.of("small", "medium", "large");
    /** What the demo runs when the caller does not choose. */
    public static final String DEFAULT_PROFILE = "medium";
    /** Default timed passes per level — enough for a stable median, well under 2s total. */
    public static final int DEFAULT_PASSES = 48;
    /** Hard ceiling the endpoint accepts; more passes buy no teaching. */
    public static final int MAX_PASSES = 400;
    /** The two levels compared: gzip's fastest vs its default — the whole trade-off on one screen. */
    public static final List<Integer> LEVELS = List.of(1, 6);
    /** Untimed passes so the JIT settles before the clock starts. */
    public static final int WARMUP_PASSES = 5;

    private static final int SMALL_BYTES = 1024;
    private static final int MEDIUM_BYTES = 4 * 1024;
    private static final int LARGE_BYTES = 16 * 1024;

    /** One gzip level's measurements: bytes before/after, the ratio, medians. */
    public record LevelResult(int level, int rawBytes, int compressedBytes, double compressedToRawRatio,
            long medianCompressMicros, long medianRoundTripMicros) {
    }

    /** The engine's half of the report; the use case adds config + reading. */
    public record RawBench(String payloadProfile, int payloadBytes, int payloadItems,
            List<LevelResult> levels) {

        public RawBench {
            levels = List.copyOf(levels);
        }
    }

    private record BuiltPayload(String json, int items) {
    }

    /** Runs every level over the same payload and hands back the measurements. */
    public RawBench benchmark(String profile, int passes) {
        BuiltPayload built = buildPayload(profile);
        byte[] raw = built.json().getBytes(StandardCharsets.UTF_8);
        List<LevelResult> results = new ArrayList<>();
        for (int level : LEVELS) {
            results.add(measureLevel(raw, level, passes));
        }
        return new RawBench(profile, raw.length, built.items(), results);
    }

    /**
     * One level, measured the honest way: compress once for the byte counts,
     * warm up untimed, then time compress and round-trip separately. The
     * ratio is deterministic (same bytes in, same bytes out); the micros are
     * medians and still move between runs — which is exactly the lesson.
     */
    private LevelResult measureLevel(byte[] raw, int level, int passes) {
        byte[] compressed = gzip(raw, level);
        for (int warmup = 0; warmup < WARMUP_PASSES; warmup++) {
            gunzip(gzip(raw, level));
        }
        long[] compressNanos = new long[passes];
        long[] roundTripNanos = new long[passes];
        for (int pass = 0; pass < passes; pass++) {
            long start = System.nanoTime();
            byte[] out = gzip(raw, level);
            long mid = System.nanoTime();
            gunzip(out);
            long end = System.nanoTime();
            compressNanos[pass] = mid - start;
            roundTripNanos[pass] = end - start;
        }
        return new LevelResult(level, raw.length, compressed.length,
                compressed.length / (double) raw.length,
                medianMicros(compressNanos), medianMicros(roundTripNanos));
    }

    /** Median of even counts averages the two middle samples — no free optimism. */
    private static long medianMicros(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        return Math.round(median / 1_000.0);
    }

    private static byte[] gzip(byte[] raw, int level) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(raw.length / 2)) {
            GZIPOutputStream gzip = new GZIPOutputStream(buffer, level);
            gzip.write(raw);
            gzip.close();
            return buffer.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("benchmark gzip failed", error);
        }
    }

    private static byte[] gunzip(byte[] compressed) {
        try (GZIPInputStream gzip = new GZIPInputStream(
                new java.io.ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("benchmark gunzip failed", error);
        }
    }

    /**
     * A real-shaped feed: repeated JSON keys, rotating prose bodies, varied
     * numbers — the compressibility of an actual API answer, not of "aaaa".
     */
    private static BuiltPayload buildPayload(String profile) {
        int targetBytes = switch (profile) {
            case "small" -> SMALL_BYTES;
            case "large" -> LARGE_BYTES;
            default -> MEDIUM_BYTES;
        };
        String[] bodies = {
                "Measure first: the profile names the hot path before any line moves, and the budget decides whether it is worth moving."
                        + " A change without a number before it is a guess wearing a hat.",
                "Threads are not free: every platform thread pins an OS thread and a stack, so pools are a rationing device."
                        + " Size them for the work, shut them down, and never leak one per request.",
                "Compression trades CPU for bytes: the level dial moves both sides, and below a size floor the trade loses money."
                        + " Medians over many passes keep the reading honest.",
                "The pool is a shared scarce resource: connections are capped by the database, not by appetite."
                        + " Waiting for a connection is still waiting — measure it like any other wait.",
                "The collector is a dial, not a savior: pick G1 or ZGC for the pause shape you need, then size the heap and leave."
                        + " GC tuning cannot rescue an allocation storm."
        };
        StringBuilder json = new StringBuilder(targetBytes + 512);
        json.append("{\"feed\":\"performance-bench\",\"note\":\"the shape of bytes a JSON API really ships\",\"items\":[");
        int items = 0;
        while (json.length() < targetBytes) {
            if (items > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(items)
                    .append(",\"title\":\"Lever ").append(items % 7).append(": read it before you pull it\"")
                    .append(",\"body\":\"").append(bodies[items % bodies.length]).append('"')
                    .append(",\"tags\":[\"performance\",\"benchmarking\",\"gzip\"]")
                    .append(",\"weight\":").append(items % 5)
                    .append(",\"pinned\":").append(items % 3 == 0)
                    .append('}');
            items++;
        }
        json.append("]}");
        return new BuiltPayload(json.toString(), items);
    }
}
