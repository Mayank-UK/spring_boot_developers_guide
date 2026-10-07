package guide.domain.performance;

import java.util.List;

/**
 * The EFFECTIVE server.compression posture, read once at the composition
 * root from the environment and carried into the domain as a plain value —
 * the benchmark reports what the running process would actually do on the
 * wire, never a hardcoded claim. (Parsing Spring's property spellings lives
 * in config/PerfDemoConfig; here there are only values.)
 */
public record ServerCompression(boolean enabled, List<String> mimeTypes, String minResponseSize) {

    public ServerCompression {
        mimeTypes = List.copyOf(mimeTypes);
    }
}
