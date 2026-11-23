package dev.materione.plugin.view;

import dev.materione.api.logic.variable.SessionVariables;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.behavior.logic.MapLogicContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A session's values for a test that builds its own logic context: {@code vars.session.<name>} reads through the
 * {@link VariableFacets#SESSION} facet (views.md section 3.4, the one spelling), so a context with no view runtime needs
 * one to show a value there.
 */
public final class TestSessionValues implements SessionVariables {

    private final Map<String, Object> values = new HashMap<>();

    private TestSessionValues(Map<String, Object> values) {
        this.values.putAll(values);
    }

    /** {@code context} with {@code values} as its session values. */
    public static MapLogicContext in(MapLogicContext context, Map<String, Object> values) {
        return context.with(VariableFacets.SESSION, new TestSessionValues(values));
    }

    @Override
    public Optional<Object> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    @Override
    public void put(String name, Object value) {
        if (value == null) {
            values.remove(name);
        } else {
            values.put(name, value);
        }
    }
}
