package guide.data.deps;

import guide.domain.deps.DependencyTreeSnapshot;
import guide.domain.deps.DependencyTreeSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter: load the checked-in dependency-tree snapshot and parse it into
 * the summarized shape. The format is the resource file's own contract:
 * '#' comments, [meta]/[tree]/[conflicts] sections, and REAL
 * maven-dependency-plugin tree lines (3-char indent groups: "+- ", "\- ",
 * "|  ", and the 3-space continuation a \- subtree uses) — kept verbatim
 * from the mvn run so nothing between maven and this parser invents data.
 */
public final class ClasspathDependencyTreeSnapshot implements DependencyTreeSource {

    private static final String RESOURCE = "/deps/dependency-tree-snapshot.txt";

    @Override
    public DependencyTreeSnapshot load() {
        String text = readResource();
        Map<String, String> meta = new LinkedHashMap<>();
        List<Node> treeLines = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String section = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            switch (section == null ? "" : section) {
                case "[meta]" -> {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }
                case "[tree]" -> treeLines.add(parseTreeLine(line));
                case "[conflicts]" -> conflicts.add(line);
                default -> throw new IllegalStateException(
                        "snapshot line outside any section: \"" + line + "\"");
            }
        }
        return assemble(meta, treeLines, conflicts);
    }

    private String readResource() {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("snapshot resource missing: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("snapshot resource unreadable: " + RESOURCE, e);
        }
    }

    // ---- the tree-line grammar (maven-dependency-plugin's own format) ----

    private record Node(int depth, String groupId, String artifactId, String version,
                        String scope, boolean optional) {
    }

    private static Node parseTreeLine(String line) {
        // prefixLength 0 is the ROOT line (guide:server:jar:…) — depth 0, no markers
        int prefixLength = prefixLength(line);
        String coordinate = line.substring(prefixLength).trim();
        boolean optional = coordinate.endsWith("(optional)");
        if (optional) {
            coordinate = coordinate.substring(0, coordinate.length() - "(optional)".length()).trim();
        }
        String[] parts = coordinate.split(":");
        if (parts.length < 4) {
            throw new IllegalStateException("unparseable coordinate: \"" + coordinate + "\"");
        }
        String scope = parts.length >= 5 ? parts[4] : null;
        return new Node(prefixLength / 3, parts[0], parts[1], parts[3], scope, optional);
    }

    /**
     * Walk the 3-char indent groups maven emits. Depth is prefix-length/3:
     * "+- " marks a child, "|  " continues a level, "\- " is the LAST
     * child, and "   " (three spaces) continues under a \- subtree.
     */
    private static int prefixLength(String line) {
        int i = 0;
        while (i + 3 <= line.length()) {
            String seg = line.substring(i, i + 3);
            if (seg.equals("+- ") || seg.equals("\\- ") || seg.equals("|  ") || seg.equals("   ")) {
                i += 3;
            } else {
                break;
            }
        }
        return i;
    }

    // ---- assemble flat nodes into the two-level summary ----

    private static DependencyTreeSnapshot assemble(Map<String, String> meta, List<Node> nodes,
                                                   List<String> conflicts) {
        if (nodes.isEmpty() || nodes.getFirst().depth() != 0) {
            throw new IllegalStateException("snapshot tree has no root line");
        }
        List<DependencyTreeSnapshot.DirectDependency> direct = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.depth() == 1) {
                direct.add(toDirect(node, childrenOf(nodes, i)));
            }
        }
        int declared = parseIntOr(meta.get("direct-count"), direct.size());
        if (declared != direct.size()) {
            throw new IllegalStateException("snapshot meta declares direct-count=" + declared
                    + " but the tree parses " + direct.size() + " direct lines");
        }
        return new DependencyTreeSnapshot(
                meta.getOrDefault("captured", "unknown"),
                meta.getOrDefault("parent-bom", "unknown"),
                meta.getOrDefault("imported-bom", "unknown"),
                direct.size(),
                List.copyOf(direct),
                List.copyOf(conflicts));
    }

    private static List<DependencyTreeSnapshot.Child> childrenOf(List<Node> nodes, int directIndex) {
        List<DependencyTreeSnapshot.Child> children = new ArrayList<>();
        int directDepth = nodes.get(directIndex).depth();
        for (int j = directIndex + 1; j < nodes.size(); j++) {
            Node node = nodes.get(j);
            if (node.depth() <= directDepth) {
                break;
            }
            if (node.depth() == directDepth + 1) {
                children.add(new DependencyTreeSnapshot.Child(
                        node.groupId(), node.artifactId(), node.version(), node.scope()));
            }
        }
        return List.copyOf(children);
    }

    private static DependencyTreeSnapshot.DirectDependency toDirect(Node node,
            List<DependencyTreeSnapshot.Child> children) {
        return new DependencyTreeSnapshot.DirectDependency(
                node.groupId(), node.artifactId(), node.version(), node.scope(), node.optional(),
                children);
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
