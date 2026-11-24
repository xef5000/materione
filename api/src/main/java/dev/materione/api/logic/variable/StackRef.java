package dev.materione.api.logic.variable;

import java.util.Map;
import java.util.UUID;

/**
 * An item stack as a variable holder (logic.md §38): the stack a run is about, as a handle the platform
 * understands and the engine only passes along. The API names no platform type, so the handle is opaque here;
 * the platform's {@link StackVariableStore} knows what it wraps.
 *
 * <p>A handle is a reference to a live stack, not a copy of its state: a read through it sees the stack's
 * current values, and a write lands on the stack the handle refers to. A trigger gives it to one run; that run
 * may bind an opened view to the same handle for the view's lifetime.
 */
public interface StackRef {

    /**
     * The player on whose thread the stack is touched (the player the run belongs to), or null when the stack
     * has no owning player. A platform that confines entities to a region uses it to decide whether the calling
     * thread may read or write the stack.
     */
    UUID owner();

    /**
     * The stack facts exposed to {@code item.*} and {@code view.item}: identity, material and amount, plus any
     * platform facts the stack supplies. A stale handle has no facts.
     */
    default Map<String, Object> itemView() {
        return Map.of();
    }
}
