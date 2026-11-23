package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableScope;
import java.util.Objects;

/** A state-key family (views.md section 5.2): a declared variable and the scope it is held in. */
public record ReadKey(VariableScope scope, NamespacedId variable) {

    public ReadKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(variable, "variable");
    }
}
