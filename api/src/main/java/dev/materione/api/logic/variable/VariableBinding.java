package dev.materione.api.logic.variable;

import dev.materione.api.NamespacedId;
import java.util.Optional;

/**
 * One run's view of the declared persistent variables: which are declared, and the stored value of a
 * declaration for the run's player (player scope) or the server (server scope). A
 * {@link dev.materione.api.logic.LogicContext} hands it out, so an expression and a {@code set_var}
 * reach the same store the run's player does, and a test supplies a fake.
 *
 * <p>An unset or offline value is empty and the caller takes the default. A live value that cannot
 * safely be read on this thread throws {@link dev.materione.api.logic.ReadUnavailableException};
 * it must not be confused with an unset value. Writes return false when the holder is unavailable.
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

    /** The stored value, or empty when unset or offline; an unsafe live read throws. */
    Optional<Object> stored(VariableDeclaration declaration);

    /**
     * Stores {@code value} (null clears it) for this run.
     *
     * @return false when there is no holder for the scope (no player, an offline player) or the write failed
     */
    boolean store(VariableDeclaration declaration, Object value);

    /**
     * The item stack this run is about (logic.md §38), as the handle the item scope reads and writes through, or empty
     * when the run has none. The stack-editing verb acts on this same handle (logic.md §41), so "the run's item" is one
     * notion: the trigger declares it, the binding holds it, and every consumer asks here.
     */
    default Optional<StackRef> stack() {
        return Optional.empty();
    }

    /** Atomically applies a pure operation with respect to every writer of this variable. */
    default VariableOps.Outcome mutate(VariableDeclaration declaration, VariableOp operation, Object operand) {
        return new VariableOps.Outcome(false, null, "atomic variable mutation is unsupported");
    }
}
