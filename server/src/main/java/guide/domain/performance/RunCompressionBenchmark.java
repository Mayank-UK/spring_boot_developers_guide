package guide.domain.performance;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Use case: one run of the perf:bench demo. Owns the parameter policy the
 * presentation edge trusts (payload must name a profile, passes must parse
 * into 1..MAX) and the report's assembly: the engine's measurements plus the
 * process's effective compression posture plus the reading that says what
 * the numbers mean. Every bad input becomes a typed VALIDATION failure —
 * the 400 envelope — never a 500.
 */
public class RunCompressionBenchmark {

    /** The wire shape the client renders: measurements, config, reading. */
    public record BenchReport(String payloadProfile, int payloadBytes, int payloadItems,
            List<CompressionBenchmark.LevelResult> levels, ServerCompression serverCompression,
            List<String> reading) {
    }

    private final CompressionBenchmark benchmark;
    private final ServerCompression serverCompression;

    public RunCompressionBenchmark(CompressionBenchmark benchmark, ServerCompression serverCompression) {
        this.benchmark = benchmark;
        this.serverCompression = serverCompression;
    }

    public Result<BenchReport> execute(String payload, String passes) {
        String profile = payload == null || payload.isBlank()
                ? CompressionBenchmark.DEFAULT_PROFILE
                : payload.trim();
        if (!CompressionBenchmark.PAYLOAD_PROFILES.contains(profile)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "payload must be one of: " + String.join(", ", CompressionBenchmark.PAYLOAD_PROFILES),
                    Map.of("parameter", "payload", "validValues", CompressionBenchmark.PAYLOAD_PROFILES)));
        }
        Integer passCount = parsePasses(passes);
        if (passCount == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "passes must be an integer between 1 and " + CompressionBenchmark.MAX_PASSES,
                    Map.of("parameter", "passes", "min", 1, "max", CompressionBenchmark.MAX_PASSES)));
        }
        CompressionBenchmark.RawBench raw = benchmark.benchmark(profile, passCount);
        return Result.success(new BenchReport(raw.payloadProfile(), raw.payloadBytes(), raw.payloadItems(),
                raw.levels(), serverCompression, reading(raw, passCount)));
    }

    /** Null/blank means "use the default"; anything else must parse and range-check. */
    static Integer parsePasses(String passes) {
        if (passes == null || passes.isBlank()) {
            return CompressionBenchmark.DEFAULT_PASSES;
        }
        try {
            int value = Integer.parseInt(passes.trim());
            if (value < 1 || value > CompressionBenchmark.MAX_PASSES) {
                return null;
            }
            return value;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private List<String> reading(CompressionBenchmark.RawBench raw, int passes) {
        List<String> lines = new ArrayList<>();
        lines.add("identity: " + raw.payloadBytes() + " bytes leave uncompressed — the floor every level is measured against.");
        for (CompressionBenchmark.LevelResult level : raw.levels()) {
            lines.add("gzip level " + level.level() + ": " + level.rawBytes() + " bytes become "
                    + level.compressedBytes() + " (" + String.format(java.util.Locale.ROOT, "%.1f",
                            level.compressedToRawRatio() * 100) + "% of identity) at a median "
                    + level.medianCompressMicros() + "µs per compress.");
        }
        lines.add("timing is medians over " + passes + " timed passes after "
                + CompressionBenchmark.WARMUP_PASSES + " warm-ups; micros move between runs, the bytes never do.");
        lines.add(serverCompression.enabled()
                ? "this process's connector compression is ON: " + serverCompression.mimeTypes() + " over "
                        + serverCompression.minResponseSize() + " ride gzip when the client sends Accept-Encoding."
                : "this process's connector compression is OFF: every response leaves at identity regardless of level.");
        lines.add("measured in-process with the JDK's java.util.zip — the deflate family the connector calls;"
                + " the terminal tour shows the wire proof with curl.");
        return List.copyOf(lines);
    }
}
