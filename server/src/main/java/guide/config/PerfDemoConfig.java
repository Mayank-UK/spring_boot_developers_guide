package guide.config;

import guide.domain.performance.CompressionBenchmark;
import guide.domain.performance.RunCompressionBenchmark;
import guide.domain.performance.ServerCompression;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Topic 38 (performance) wiring — the perf:bench demo's composition root.
 * The one Spring-side job worth teaching lives here: the benchmark must
 * report the EFFECTIVE server.compression posture, not a claim baked into
 * prose — so the three property spellings are read once, at startup, from
 * the real Environment (application.yml plus any env overrides), parsed
 * into the domain's {@link ServerCompression} value, and everything
 * downstream stays framework-free.
 *
 * mime-types is deliberately stored as ONE comma-separated string in
 * application.yml: relaxed binding still delivers it to Boot as a list,
 * AND a plain Environment read returns it — the demo and the connector
 * agree on the same line.
 */
@Configuration
public class PerfDemoConfig {

    private static final String ENABLED_KEY = "server.compression.enabled";
    private static final String MIME_TYPES_KEY = "server.compression.mime-types";
    private static final String MIN_SIZE_KEY = "server.compression.min-response-size";

    @Bean
    ServerCompression serverCompression(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty(ENABLED_KEY, "false"));
        String mimeTypes = environment.getProperty(MIME_TYPES_KEY, "");
        String minResponseSize = environment.getProperty(MIN_SIZE_KEY, "2KB");
        return new ServerCompression(enabled,
                Arrays.stream(mimeTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                minResponseSize);
    }

    @Bean
    RunCompressionBenchmark runCompressionBenchmark(ServerCompression serverCompression) {
        return new RunCompressionBenchmark(new CompressionBenchmark(), serverCompression);
    }
}
