package guide.domain.arch;

import java.util.List;

/**
 * Port: read the codebase's own shape from the compiled classes — the layer
 * map. Counting classes and walking interface hierarchies is CLASSPATH
 * MACHINERY (classloader resources, reflection), so it belongs behind a
 * port with an adapter in data/, exactly like a database or an HTTP client:
 * the domain states WHAT it wants to know about its own structure, the
 * adapter decides HOW to find out. The map is computed at request time from
 * the running application — never a hand-maintained diagram that drifts.
 */
public interface CodebaseMapSource {

    CodebaseMap read();

    /** One architectural layer: its name, package root, class count, and per-area counts. */
    record LayerCount(String layer, String packageRoot, int classes, List<AreaCount> areas) {
    }

    /** Classes in one area (the package segment under the layer root). */
    record AreaCount(String area, int classes) {
    }

    /** One domain port interface: its name and the data-layer adapters + config bindings found for it. */
    record PortBinding(String port, List<String> adapters, List<String> bindings) {
    }

    /** The raw map: layers with counts, and the port→adapter inventory. */
    record CodebaseMap(List<LayerCount> layers, List<PortBinding> ports) {
    }
}
