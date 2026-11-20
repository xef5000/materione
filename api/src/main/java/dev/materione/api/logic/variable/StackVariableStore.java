package dev.materione.api.logic.variable;

import java.util.Optional;

/**
 * The storage port for {@link VariableScope#ITEM} (logic.md §38): the same contract as {@link VariableStore} with
 * the holder a {@link StackRef} instead of a player id. There is one port per holder kind; a holder that is not a
 * player id cannot ride {@code UUID}-keyed methods, and generalizing {@link VariableStore}'s holder is
 * the keyed-state design of §38 (step 2), not this port's job.
 *
 * <p>Implementations hold values, never defaults: an unset value reads as empty, and the caller supplies the
 * declaration's default. Values are already of the declared type. A write that stores nothing observable (a value
 * equal to the default) may clear the key instead, so two stacks in the same effective state also share the same
 * bytes and stack together.
 */
public interface StackVariableStore {

    /** Always {@link VariableScope#ITEM}. */
    default VariableScope scope() {
        return VariableScope.ITEM;
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
