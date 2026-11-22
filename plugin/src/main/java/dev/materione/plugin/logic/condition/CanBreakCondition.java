package dev.materione.plugin.logic.condition;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.plugin.logic.world.WorldVerbs;

/** {@code can_break} (logic.md §43): may the player, or the world, break the block at a location? See {@link ProtectionCondition}. */
public final class CanBreakCondition extends ProtectionCondition {

    public CanBreakCondition(WorldVerbs verbs) {
        super("can_break", "Can break", "the actor may not break the block there", ProtectionAction.BREAK, verbs);
    }
}
