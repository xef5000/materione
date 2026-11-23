package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.logic.MapLogicContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A session's values for a test that builds its own logic context: {@code vars.session.<pack>.<key>} reads through a
 * variable binding whose session scope holds {@code values} (views.md section 3.4), so a context with no view runtime
 * needs one to show a value there. Every key of the pack is a declared session variable typed by its value.
 */
public final class TestSessionValues {

    private TestSessionValues() {}

    /** {@code context} with {@code values} as the session variables of {@code pack}. */
    public static MapLogicContext in(MapLogicContext context, String pack, Map<String, Object> values) {
        var held = new HashMap<>(values);
        return context.withVariables(new VariableBinding() {
            @Override
            public Optional<VariableDeclaration> declaration(NamespacedId id) {
                if (!id.namespace().equals(pack)) {
                    return Optional.empty();
                }
                Object sample = held.get(id.value());
                VariableType type = sample instanceof Boolean ? VariableType.BOOLEAN
                        : sample instanceof Number ? VariableType.NUMBER : VariableType.STRING;
                return Optional.of(new VariableDeclaration(id, VariableScope.SESSION, type, type.defaultValue(), ""));
            }

            @Override
            public Optional<Object> stored(VariableDeclaration declaration) {
                return Optional.ofNullable(held.get(declaration.id().value()));
            }

            @Override
            public boolean store(VariableDeclaration declaration, Object value) {
                held.put(declaration.id().value(), value);
                return true;
            }
        });
    }
}
