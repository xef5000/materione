package dev.materione.behavior.rule;

/**
 * The normalized event taxonomy (runtime-systems §2.1). Every content type
 * contributes events from this one vocabulary — a menu button click and an
 * item right-click are the same {@code click} event carrying different
 * context fields. M2's runtime fires the world/item subset; later milestones
 * light up menus/HUDs/dialogs/tick against the same selectors.
 */
public final class EventTypes {

    public static final String CLICK = "click";
    public static final String BREAK = "break";
    public static final String PLACE = "place";
    public static final String INTERACT = "interact";
    public static final String EQUIP = "equip";
    public static final String UNEQUIP = "unequip";
    public static final String DROP = "drop";
    public static final String PICKUP = "pickup";
    public static final String INV_CLICK = "inv_click";
    public static final String DEATH = "death";
    public static final String CONSUME = "consume";
    public static final String DAMAGE = "damage";
    public static final String TICK = "tick";
    public static final String MENU_CLICK = "menu.click";
    public static final String HUD_THRESHOLD = "hud.threshold";
    public static final String DIALOG_SUBMIT = "dialog.submit";
    public static final String CUSTOM = "custom";

    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            CLICK, BREAK, PLACE, INTERACT, EQUIP, UNEQUIP, DROP, PICKUP,
            INV_CLICK, DEATH, CONSUME, DAMAGE, TICK,
            MENU_CLICK, HUD_THRESHOLD, DIALOG_SUBMIT, CUSTOM);

    private EventTypes() {}

    private static final java.util.List<String> ITEM = java.util.List.of("item");
    private static final java.util.List<String> NONE = java.util.List.of();

    /**
     * The taxonomy as the editor explains it. {@code firedBy} names the content
     * types whose runtime fires each event today (item listeners, the block and
     * furniture services); an event with an empty list is part of the
     * taxonomy but nothing fires it yet, so the editor must not offer it as a
     * working trigger. Keep this table in step with the firing sites.
     */
    public static java.util.List<dev.materione.api.registry.BehaviorVocabulary.Event> describe() {
        return java.util.List.of(
                event(CLICK, "Click",
                        "The player left- or right-clicks with the item, or right-clicks the furniture",
                        java.util.List.of("right", "left"), java.util.List.of("item", "furniture")),
                event(BREAK, "Break", "The player breaks a block while holding the item, or breaks this block",
                        NONE, java.util.List.of("item", "block")),
                event(PLACE, "Place", "The block or furniture is placed",
                        NONE, java.util.List.of("block", "furniture")),
                event(INTERACT, "Interact", "The player left- or right-clicks the placed block",
                        java.util.List.of("right", "left"), java.util.List.of("block")),
                event(EQUIP, "Equip", "The item becomes the held item", NONE, ITEM),
                event(UNEQUIP, "Unequip", "The item stops being the held item", NONE, ITEM),
                event(DROP, "Drop", "The player drops the item", NONE, ITEM),
                event(PICKUP, "Pick up", "The player picks the item up", NONE, ITEM),
                event(CONSUME, "Consume", "The player eats or drinks the item", NONE, ITEM),
                event(DAMAGE, "Damage", "The item takes durability damage", NONE, ITEM),
                event(DEATH, "Death", "The player dies carrying the item", NONE, ITEM),
                event(INV_CLICK, "Inventory click", "Reserved; not fired yet", NONE, NONE),
                event(TICK, "Tick", "Reserved; not fired yet", NONE, NONE),
                event(MENU_CLICK, "Menu click", "Reserved; not fired yet", NONE, NONE),
                event(HUD_THRESHOLD, "HUD threshold", "Reserved; not fired yet", NONE, NONE),
                event(DIALOG_SUBMIT, "Dialog submit", "Reserved; not fired yet", NONE, NONE),
                event(CUSTOM, "Custom", "Reserved; not fired yet", NONE, NONE));
    }

    private static dev.materione.api.registry.BehaviorVocabulary.Event event(
            String id, String name, String description, java.util.List<String> details,
            java.util.List<String> firedBy) {
        return new dev.materione.api.registry.BehaviorVocabulary.Event(id, name, description, details, firedBy);
    }

    /** True when {@code type} names an event in the taxonomy (parse warns on typos). */
    public static boolean isKnown(String type) {
        return KNOWN.contains(type);
    }
}
