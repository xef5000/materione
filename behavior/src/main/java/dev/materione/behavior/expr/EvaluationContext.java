package dev.materione.behavior.expr;

import java.util.List;
import java.util.Map;

/** The expression-visible variable space (runtime-systems §2.2): dotted paths walk nested maps. */
public final class EvaluationContext {

    private final Map<String, Object> root;

    public EvaluationContext(Map<String, Object> root) {
        this.root = root;
    }

    public Map<String, Object> root() {
        return root;
    }

    /**
     * Walks {@code segments} through nested maps. A missing segment or a
     * scalar mid-path resolves to {@code null} — absent variables are data,
     * not errors.
     */
    public Object resolve(List<String> segments) {
        Object current = root;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
