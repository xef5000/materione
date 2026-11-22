package dev.materione.plugin.logic.condition;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.plugin.logic.world.WorldVerbs;

/**
 * {@code can_interact} (logic.md §43): may the player, or the world, open or press what is at a location? See
 * {@link ProtectionCondition}.
 */
public final class CanInteractCondition extends ProtectionCondition {

    public CanInteractCondition(WorldVerbs verbs) {
        super("can_interact", "Can interact", "the actor may not open or press what is there", ProtectionAction.INTERACT,
                verbs);
    }
}
