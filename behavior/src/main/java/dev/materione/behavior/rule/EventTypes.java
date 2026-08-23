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

    /** True when {@code type} names an event in the taxonomy (parse warns on typos). */
    public static boolean isKnown(String type) {
        return KNOWN.contains(type);
    }
}
