package dev.materione.core.pipeline.graph;

import dev.materione.api.NamespacedId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The validated content graph (master §6.1): nodes are resources, edges are
 * typed references, and every node remembers its source file (lineage —
 * scope expansion walks edges backwards: change a texture, learn its items).
 *
 * <p>Edges to targets that do not exist are recorded as {@link Unresolved}
 * instead of being dropped: level-3 validation turns them into diagnostics
 * with "did you mean" suggestions, never into crashes.
 */
public final class ContentGraph {

    private final java.util.Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Unresolved> unresolved = new ArrayList<>();

    /** One graph node. */
    public record Node(NamespacedId id, String contentType, Path sourceFile) {}

    /** One typed edge. */
    public record Edge(NamespacedId from, NamespacedId to, ContentEdgeType type) {}

    /** An edge whose target is not (yet) a node. */
    public record Unresolved(NamespacedId from, NamespacedId missingTarget, ContentEdgeType type, Path sourceFile) {}

    /**
     * Nodes are keyed by content type and id together: an item
     * {@code sample:ruby_sword} and its recipe {@code sample:ruby_sword} are
     * distinct resources (master §7.2 — files hold one content type each).
     */
    public synchronized void addNode(NamespacedId id, String contentType, Path sourceFile) {
        nodes.put(contentType + "|" + id, new Node(id, contentType, sourceFile));
    }

    public synchronized void addEdge(NamespacedId from, NamespacedId to, ContentEdgeType type) {
        if (node(from).isPresent() && node(to).isPresent()) {
            edges.add(new Edge(from, to, type));
        } else {
            Path source = node(from).map(Node::sourceFile).orElse(null);
            unresolved.add(new Unresolved(from, to, type, source));
        }
    }

    private static String keyOf(NamespacedId id) {
        return "|" + id;
    }

    /** The first node with this id, any content type. */
    public synchronized Optional<Node> node(NamespacedId id) {
        return nodes.values().stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public synchronized Optional<Node> node(NamespacedId id, String contentType) {
        return Optional.ofNullable(nodes.get(contentType + "|" + id));
    }

    public synchronized List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public synchronized List<Edge> edges() {
        return List.copyOf(edges);
    }

    public synchronized List<Unresolved> unresolved() {
        return List.copyOf(unresolved);
    }

    /** Everything that references {@code target} through an edge (scope expansion, §4.1). */
    public synchronized List<Node> referencing(NamespacedId target) {
        var referencers = new ArrayList<Node>();
        for (Edge edge : edges) {
            if (edge.to().equals(target) && nodes.containsKey(edge.from())) {
                referencers.add(nodes.get(edge.from()));
            }
        }
        return referencers;
    }

    public synchronized int nodeCount() {
        return nodes.size();
    }
}
