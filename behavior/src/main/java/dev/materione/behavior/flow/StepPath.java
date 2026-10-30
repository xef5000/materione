package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticTarget;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a step, a condition or a rule sits inside a resource, as the path of keys and list indexes
 * that reaches it in the YAML: {@code behavior.2.do.0.then.1} is the second step of the
 * {@code then} branch of the first step in the third rule's {@code do} list (logic.md §5.1). The
 * compiler stamps one on every step and every diagnostic, so the studio and {@code validate} can
 * point at a node, and the run trace names the same paths.
 *
 * <p>Immutable. The segments are exactly the keys a reader of the file would follow, so the path
 * doubles as the diagnostic {@linkplain DiagnosticTarget target}.
 */
public final class StepPath {

    private static final StepPath ROOT = new StepPath(List.of());

    private final List<String> segments;

    private StepPath(List<String> segments) {
        this.segments = segments;
    }

    /** The empty path. */
    public static StepPath root() {
        return ROOT;
    }

    /** The path made of {@code segments}. */
    public static StepPath of(String... segments) {
        return new StepPath(List.of(segments));
    }

    /** This path followed by a key. */
    public StepPath child(String key) {
        var next = new ArrayList<String>(segments.size() + 1);
        next.addAll(segments);
        next.add(key);
        return new StepPath(List.copyOf(next));
    }

    /** This path followed by a list index. */
    public StepPath child(int index) {
        return child(Integer.toString(index));
    }

    /** The keys and indexes, outermost first. */
    public List<String> segments() {
        return segments;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /** The diagnostic target for this path, or {@code null} for the empty path. */
    public DiagnosticTarget target() {
        return segments.isEmpty() ? null : new DiagnosticTarget(segments);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StepPath path && path.segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    /** The dotted form, {@code behavior.2.do.0.then.1}. */
    @Override
    public String toString() {
        return String.join(".", segments);
    }
}
