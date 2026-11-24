package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.VariableDeclaration;
import java.util.UUID;

/**
 * Told after every successful write to a declared variable (views.md section 5.3). Called on the writer's thread,
 * which may be any thread, so an implementation reads immutable data and touches no state an owner thread owns.
 */
@FunctionalInterface
public interface VariableWriteObserver {

    /** Nobody is watching. */
    VariableWriteObserver NONE = (declaration, holder) -> { };

    /**
     * A write succeeded.
     *
     * @param declaration the variable written
     * @param holder the player for {@code player} scope, the view instance for {@code session} scope, null for
     *     {@code server}
     */
    void written(VariableDeclaration declaration, UUID holder);
}
