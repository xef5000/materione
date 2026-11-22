package dev.materione.plugin.logic.world;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.plugin.logic.variable.BukkitStackRef;
import java.util.Optional;

/**
 * "The stack the run is about" for the verbs that need the platform stack behind it (logic.md §38 decision 4, §41): the
 * one handle the run's binding holds ({@code VariableBinding#stack}), which the item scope reads and writes through.
 * The verbs ask it here and never carry a notion of their own.
 */
public final class RunStacks {

    private RunStacks() {}

    /** The run's stack handle, when the trigger gave it one and the platform can edit it. */
    public static Optional<BukkitStackRef> of(LogicContext context) {
        Optional<StackRef> stack = context.variables().stack();
        return stack.filter(BukkitStackRef.class::isInstance).map(BukkitStackRef.class::cast);
    }

    /** The Materione identity of the run's stack, or empty for none or for a vanilla item. */
    public static Optional<String> identity(LogicContext context) {
        return of(context).map(BukkitStackRef::identity).filter(id -> !id.isBlank());
    }
}
