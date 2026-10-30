package dev.materione.behavior.logic;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.rule.EventTypes;
import java.util.List;

/**
 * The normalized event taxonomy (runtime-systems §2.1) as built-in {@link TriggerType}
 * registrations (logic.md §4.3). It replaces the old {@code EventTypes} description table: the
 * plugin registers these through {@code LogicApi.triggers()} the way an addon registers its own.
 *
 * <p>{@code firedBy} names the content types whose runtime fires each trigger today (item
 * listeners, the block and furniture services); a trigger with an empty list is part of the
 * taxonomy but nothing fires it yet, so the editor must not offer it as a working trigger. Keep
 * this table in step with the firing sites. {@code cancellable} mirrors whether the firing site
 * can honour a cancel request: the break, interact, click, equip, drop, pickup, consume and damage
 * sites hand the engine a cancellable Bukkit event; place and death do not.
 */
public final class BuiltinTriggers {

    private static final List<String> ITEM = List.of("item");
    private static final List<String> NONE = List.of();

    private BuiltinTriggers() {}

    /** The seventeen built-in triggers, in the order the editor lists them. */
    public static List<TriggerType> all() {
        return List.of(
                trigger(EventTypes.CLICK, "Click",
                        "The player left- or right-clicks with the item, or right-clicks the furniture",
                        List.of("right", "left"), List.of("item", "furniture"), true),
                trigger(EventTypes.BREAK, "Break",
                        "The player breaks a block while holding the item, or breaks this block",
                        NONE, List.of("item", "block"), true),
                trigger(EventTypes.PLACE, "Place", "The block or furniture is placed",
                        NONE, List.of("block", "furniture"), false),
                trigger(EventTypes.INTERACT, "Interact", "The player left- or right-clicks the placed block",
                        List.of("right", "left"), List.of("block"), true),
                trigger(EventTypes.EQUIP, "Equip", "The item becomes the held item", NONE, ITEM, true),
                trigger(EventTypes.UNEQUIP, "Unequip", "The item stops being the held item", NONE, ITEM, true),
                trigger(EventTypes.DROP, "Drop", "The player drops the item", NONE, ITEM, true),
                trigger(EventTypes.PICKUP, "Pick up", "The player picks the item up", NONE, ITEM, true),
                trigger(EventTypes.CONSUME, "Consume", "The player eats or drinks the item", NONE, ITEM, true),
                trigger(EventTypes.DAMAGE, "Damage", "The item takes durability damage", NONE, ITEM, true),
                trigger(EventTypes.DEATH, "Death", "The player dies carrying the item", NONE, ITEM, false),
                reserved(EventTypes.INV_CLICK, "Inventory click"),
                reserved(EventTypes.TICK, "Tick"),
                reserved(EventTypes.MENU_CLICK, "Menu click"),
                reserved(EventTypes.HUD_THRESHOLD, "HUD threshold"),
                reserved(EventTypes.DIALOG_SUBMIT, "Dialog submit"),
                reserved(EventTypes.CUSTOM, "Custom"));
    }

    /**
     * The variables the M2 firing sites put in every rule's context. The hosts column records which
     * runtime fills each one: the item bridge builds the richest tree, the block and furniture
     * services a smaller one. Rules read them as {@code ${player.name}}, {@code item.amount > 1}.
     */
    static List<ContextVariable> m2Context() {
        return List.of(
                ContextVariable.of("player.name", ParameterType.STRING, "The player's name"),
                ContextVariable.of("player.uuid", ParameterType.STRING, "The player's UUID"),
                ContextVariable.of("player.gamemode", ParameterType.STRING, "survival, creative, adventure or spectator"),
                ContextVariable.of("player.sneaking", ParameterType.BOOLEAN, "Whether the player is sneaking")
                        .providedBy("item", "block"),
                ContextVariable.of("player.op", ParameterType.BOOLEAN, "Whether the player is an operator")
                        .providedBy("item", "block"),
                ContextVariable.of("player.health", ParameterType.DECIMAL, "The player's health").providedBy("item"),
                ContextVariable.of("player.level", ParameterType.INTEGER, "The player's experience level")
                        .providedBy("item"),
                ContextVariable.of("player.x", ParameterType.DECIMAL, "The player's X coordinate").providedBy("item"),
                ContextVariable.of("player.y", ParameterType.DECIMAL, "The player's Y coordinate").providedBy("item"),
                ContextVariable.of("player.z", ParameterType.DECIMAL, "The player's Z coordinate").providedBy("item"),
                ContextVariable.of("player.yaw", ParameterType.DECIMAL, "The player's yaw").providedBy("item"),
                ContextVariable.of("player.pitch", ParameterType.DECIMAL, "The player's pitch").providedBy("item"),
                ContextVariable.of("location.x", ParameterType.DECIMAL, "X of the event location"),
                ContextVariable.of("location.y", ParameterType.DECIMAL, "Y of the event location"),
                ContextVariable.of("location.z", ParameterType.DECIMAL, "Z of the event location"),
                ContextVariable.of("location.world", ParameterType.STRING, "World name of the event location"),
                ContextVariable.of("world.name", ParameterType.STRING, "World name").providedBy("item", "block"),
                ContextVariable.of("world.time", ParameterType.INTEGER, "World time in ticks")
                        .providedBy("item", "block"),
                ContextVariable.of("item.id", ParameterType.STRING, "The Materione item id, blank for a vanilla item")
                        .providedBy("item"),
                ContextVariable.of("item.material", ParameterType.STRING, "The item's vanilla material, lowercase")
                        .providedBy("item"),
                ContextVariable.of("item.amount", ParameterType.INTEGER, "The stack size").providedBy("item"),
                ContextVariable.of("item.durability_used", ParameterType.INTEGER,
                        "Durability used, once the durability mechanic has counted").providedBy("item"),
                ContextVariable.of("item.durability_max", ParameterType.INTEGER,
                        "Maximum durability, once the durability mechanic has counted").providedBy("item"),
                ContextVariable.of("block.id", ParameterType.STRING, "The custom block's id").providedBy("block"),
                ContextVariable.of("block.technique", ParameterType.STRING, "The block's state-space technique")
                        .providedBy("block"),
                ContextVariable.of("furniture.id", ParameterType.STRING, "The furniture's id").providedBy("furniture"),
                ContextVariable.of("server.online", ParameterType.INTEGER, "Players online").providedBy("item", "block"),
                ContextVariable.of("server.max", ParameterType.INTEGER, "Player slots").providedBy("item", "block"),
                ContextVariable.of("vars.*", ParameterType.RAW, "Variables written earlier in this run by set_var"));
    }

    private static TriggerType trigger(String id, String name, String description, List<String> details,
            List<String> firedBy, boolean cancellable) {
        return new StaticTrigger(id, new TriggerDescription(
                SelfDescription.of(name, description), details, firedBy, List.of(),
                contextFor(firedBy), cancellable));
    }

    /**
     * The variables a trigger's rules can read: the ones its firing hosts fill. A trigger nothing
     * fires yet has no host, so it promises nothing.
     */
    private static List<ContextVariable> contextFor(List<String> firedBy) {
        if (firedBy.isEmpty()) {
            return List.of();
        }
        return m2Context().stream()
                .filter(variable -> variable.hosts().isEmpty()
                        || variable.hosts().stream().anyMatch(firedBy::contains))
                .toList();
    }

    private static TriggerType reserved(String id, String name) {
        return trigger(id, name, "Reserved; not fired yet", NONE, NONE, false);
    }

    /** A trigger that is only its description. */
    private record StaticTrigger(String id, TriggerDescription description) implements TriggerType {
        @Override
        public TriggerDescription describe() {
            return description;
        }
    }
}
