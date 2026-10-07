package guide.data.arch;

import guide.domain.arch.CodebaseMapSource;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.springframework.context.annotation.Bean;

/**
 * Adapter: answers the CodebaseMapSource port by scanning the RUNNING
 * application's own classes — classloader resources per layer root, then
 * reflection (never initializing) for the port/adapter walk: every interface
 * under guide.domain is a candidate port; every guide.data class whose
 * interface closure names that port is one of its adapters; every config
 * @Bean method whose declared return type IS the port is its composition-root
 * binding. The map is therefore computed, never hand-maintained — a diagram
 * that cannot drift because it is derived from the artifact it describes.
 *
 * Two deliberate scope choices: test-classes roots are skipped when main
 * roots exist (the map describes the application, not its test suite), and
 * nested classes (Foo$Bar) are excluded — the counts are top-level types,
 * which is what a reader compares against the source tree.
 */
public final class ClasspathMapAdapter implements CodebaseMapSource {

    /** Layer roots in dependency-rule order: inward first. */
    private static final Map<String, String> LAYER_ROOTS = LinkedHashMap.newLinkedHashMap(8);

    static {
        LAYER_ROOTS.put("core", "guide.core");
        LAYER_ROOTS.put("domain", "guide.domain");
        LAYER_ROOTS.put("data", "guide.data");
        LAYER_ROOTS.put("presentation", "guide.presentation");
        LAYER_ROOTS.put("config", "guide.config");
        LAYER_ROOTS.put("content", "guide.content");
    }

    @Override
    public CodebaseMap read() {
        try {
            ClassLoader loader = loader();
            Map<String, Set<String>> classesByRoot = new LinkedHashMap<>();
            Map<String, Set<String>> areasByRoot = new LinkedHashMap<>();
            for (Map.Entry<String, String> layer : LAYER_ROOTS.entrySet()) {
                Set<String> fqns = new TreeSet<>();
                Set<String> areas = new TreeSet<>();
                collect(layer.getValue(), loader, fqns, areas);
                classesByRoot.put(layer.getKey(), fqns);
                areasByRoot.put(layer.getKey(), areas);
            }
            return new CodebaseMap(layers(classesByRoot, areasByRoot), ports(classesByRoot, loader));
        } catch (IOException failure) {
            throw new IllegalStateException("the classpath scan failed: " + failure.getMessage(), failure);
        }
    }

    // ---- structure: per-layer class and area counts ----

    private static List<LayerCount> layers(Map<String, Set<String>> classesByRoot,
            Map<String, Set<String>> areasByRoot) {
        List<LayerCount> layers = new ArrayList<>();
        for (Map.Entry<String, String> layer : LAYER_ROOTS.entrySet()) {
            Set<String> fqns = classesByRoot.get(layer.getKey());
            Map<String, Integer> counts = new TreeMap<>();
            for (String area : areasByRoot.get(layer.getKey())) {
                String prefix = layer.getValue() + "." + area + ".";
                counts.put(area, (int) fqns.stream().filter(fqn -> fqn.startsWith(prefix)).count());
            }
            layers.add(new LayerCount(layer.getKey(), layer.getValue(), fqns.size(),
                    counts.entrySet().stream()
                            .map(entry -> new AreaCount(entry.getKey(), entry.getValue()))
                            .toList()));
        }
        return layers;
    }

    // ---- inventory: ports, adapters, bindings ----

    private static List<PortBinding> ports(Map<String, Set<String>> classesByRoot, ClassLoader loader) {
        Set<String> domainPorts = new TreeSet<>();
        for (String fqn : classesByRoot.get("domain")) {
            Class<?> type = load(fqn, loader);
            if (type != null && type.isInterface()) {
                domainPorts.add(fqn);
            }
        }
        Map<String, List<String>> adaptersByPort = new TreeMap<>();
        for (String fqn : classesByRoot.get("data")) {
            Class<?> type = load(fqn, loader);
            if (type == null) {
                continue;
            }
            for (String implemented : interfaceClosure(type)) {
                if (domainPorts.contains(implemented)) {
                    adaptersByPort.computeIfAbsent(implemented, key -> new ArrayList<>()).add(fqn);
                }
            }
        }
        Map<String, List<String>> bindingsByPort = new TreeMap<>();
        for (String fqn : classesByRoot.get("config")) {
            Class<?> type = load(fqn, loader);
            if (type == null) {
                continue;
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class) && domainPorts.contains(
                        method.getReturnType().getName())) {
                    bindingsByPort.computeIfAbsent(method.getReturnType().getName(), key -> new ArrayList<>())
                            .add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        List<PortBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, List<String>> port : adaptersByPort.entrySet()) {
            if (!port.getValue().isEmpty()) {
                bindings.add(new PortBinding(port.getKey(), List.copyOf(port.getValue()),
                        bindingsByPort.getOrDefault(port.getKey(), List.of())));
            }
        }
        return bindings;
    }

    /** Every interface a type implements, directly or through superclasses and superinterfaces. */
    private static Set<String> interfaceClosure(Class<?> type) {
        Set<String> closure = new TreeSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            collectInterfaces(current, closure);
        }
        return closure;
    }

    private static void collectInterfaces(Class<?> type, Set<String> closure) {
        for (Class<?> implemented : type.getInterfaces()) {
            if (closure.add(implemented.getName())) {
                collectInterfaces(implemented, closure);
            }
        }
    }

    private static Class<?> load(String fqn, ClassLoader loader) {
        try {
            return Class.forName(fqn, false, loader);
        } catch (ClassNotFoundException unresolved) {
            return null;
        }
    }

    // ---- class discovery: classloader resources, main scope only ----

    private static void collect(String root, ClassLoader loader, Set<String> fqns, Set<String> areas)
            throws IOException {
        String resourcePath = root.replace('.', '/');
        List<URL> urls = mainUrlsOnly(Collections.list(loader.getResources(resourcePath)));
        for (URL url : urls) {
            if ("file".equals(url.getProtocol())) {
                collectFromDirectory(root, Path.of(URI.create(url.toString())), fqns, areas);
            } else {
                collectFromJar(root, resourcePath, url, fqns, areas);
            }
        }
    }

    /** Drops test-classes roots when main roots exist — the map describes the application. */
    private static List<URL> mainUrlsOnly(List<URL> urls) {
        List<URL> main = urls.stream().filter(url -> !url.getPath().contains("test-classes")).toList();
        return main.isEmpty() ? urls : main;
    }

    private static void collectFromDirectory(String root, Path directory, Set<String> fqns,
            Set<String> areas) throws IOException {
        try (var files = Files.walk(directory)) {
            files.filter(Files::isRegularFile)
                    .map(directory::relativize)
                    .filter(relative -> relative.toString().endsWith(".class"))
                    .filter(relative -> !relative.toString().contains("$"))
                    .filter(relative -> !relative.getFileName().toString().startsWith("package-info"))
                    .forEach(relative -> {
                        String fqn = root + "." + relative.toString()
                                .replace(".class", "").replace('/', '.');
                        fqns.add(fqn);
                        areas.add(areaOf(relative.toString()));
                    });
        }
    }

    private static void collectFromJar(String root, String resourcePath, URL url, Set<String> fqns,
            Set<String> areas) throws IOException {
        String spec = url.toString(); // jar:file:/path.jar!guide/domain
        String jarLocation = spec.substring("jar:file:".length(), spec.indexOf("!/"));
        try (JarFile jar = new JarFile(URI.create("file:" + jarLocation).getPath())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(resourcePath + "/") || !name.endsWith(".class")
                        || name.contains("$") || name.endsWith("package-info.class")) {
                    continue;
                }
                String relative = name.substring(resourcePath.length() + 1,
                        name.length() - ".class".length());
                fqns.add(root + "." + relative.replace('/', '.'));
                areas.add(areaOf(relative));
            }
        }
    }

    private static String areaOf(String relativeClassPath) {
        int slash = relativeClassPath.indexOf('/');
        return slash < 0 ? "(root)" : relativeClassPath.substring(0, slash);
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClasspathMapAdapter.class.getClassLoader();
    }
}
