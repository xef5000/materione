package dev.materione.plugin.logic.condition;

import dev.materione.api.protection.ProtectionAction;
import dev.materione.plugin.logic.world.WorldVerbs;

/** {@code can_build} (logic.md §43): may the player, or the world, place a block at a location? See {@link ProtectionCondition}. */
public final class CanBuildCondition extends ProtectionCondition {

    public CanBuildCondition(WorldVerbs verbs) {
        super("can_build", "Can build", "the actor may not place a block there", ProtectionAction.PLACE, verbs);
    }
}
