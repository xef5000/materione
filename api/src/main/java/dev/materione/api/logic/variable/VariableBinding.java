package dev.materione.api.logic.variable;

import dev.materione.api.NamespacedId;
import java.util.Optional;

/**
 * One run's view of the declared persistent variables: which are declared, and the stored value of a
 * declaration for the run's player (player scope) or the server (server scope). A
 * {@link dev.materione.api.logic.LogicContext} hands it out, so an expression and a {@code set_var}
 * reach the same store the run's player does, and a test supplies a fake.
 *
 * <p>Reads never throw: an unset or unavailable value is empty and the caller takes the default.
 * Writes return false when the holder is unavailable, and the action fails.
 */
public interface VariableBinding {

    /** A binding with no declarations and no holders: every read is empty, every write is refused. */
    VariableBinding NONE = new VariableBinding() {
        @Override
        public Optional<VariableDeclaration> declaration(NamespacedId id) {
            return Optional.empty();
        }

        @Override
        public Optional<Object> stored(VariableDeclaration declaration) {
            return Optional.empty();
        }

        @Override
        public boolean store(VariableDeclaration declaration, Object value) {
            return false;
        }
    };

    /** The declaration of {@code id}, or empty when no pack declares it (live at the moment of the call). */
    Optional<VariableDeclaration> declaration(NamespacedId id);

    /** The stored value of {@code declaration} for this run, or empty when unset or unavailable. */
    Optional<Object> stored(VariableDeclaration declaration);

    /**
     * Stores {@code value} (null clears it) for this run.
     *
     * @return false when there is no holder for the scope (no player, an offline player) or the write failed
     */
    boolean store(VariableDeclaration declaration, Object value);
}
