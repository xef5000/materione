package dev.materione.api.logic.variable;

import java.util.Optional;

/**
 * The stack-specialized view of the {@link VariableStore} port for {@link VariableScope#ITEM} (logic.md §38).
 * The existing port still owns the store lifecycle and observation contract; this interface adds the
 * {@link StackRef} overloads without changing a {@code UUID}-keyed addon. A fully generalized holder belongs
 * to keyed state in §38 (step 2).
 *
 * <p>Implementations hold values, never defaults: an unset value reads as empty, and the caller supplies the
 * declaration's default. Values are already of the declared type. A write that stores nothing observable (a value
 * equal to the default) may clear the key instead, so two stacks in the same effective state also share the same
 * bytes and stack together.
 */
public interface StackVariableStore extends VariableStore {

    /** Always {@link VariableScope#ITEM}. */
    default VariableScope scope() {
        return VariableScope.ITEM;
    }

    /** A stack store has no UUID holder; the stack-aware methods below are its contract. */
    @Override
    default Optional<Object> read(VariableDeclaration declaration, java.util.UUID holder) {
        return Optional.empty();
    }

    /** A stack store refuses a UUID holder; callers provide the run's {@link StackRef}. */
    @Override
    default boolean write(VariableDeclaration declaration, java.util.UUID holder, Object value) {
        return false;
    }

    /**
     * The stored value, or empty when none is stored or the stack is gone. A live read that cannot safely be done
     * on this thread throws {@link dev.materione.api.logic.ReadUnavailableException}.
     */
    Optional<Object> read(VariableDeclaration declaration, StackRef stack);

    /**
     * Stores {@code value}, or clears the stored value when it is null.
     *
     * @return false when the stack cannot be written (gone, owner offline or not on this thread) or the write failed
     */
    boolean write(VariableDeclaration declaration, StackRef stack, Object value);
}
