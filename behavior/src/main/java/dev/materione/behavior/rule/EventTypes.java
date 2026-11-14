package dev.materione.behavior.rule;

/**
 * The names of the normalized event taxonomy (runtime-systems §2.1) that the firing sites use.
 * Every content type contributes events from this one vocabulary — a menu button click and an item
 * right-click are the same {@code click} event carrying different context fields.
 *
 * <p>The taxonomy itself, with descriptions, details and who fires what, is the set of built-in
 * triggers in {@link dev.materione.behavior.logic.BuiltinTriggers}; this class only holds the
 * constants so a firing site cannot misspell an event.
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

    // world events (logic.md §37): fired for item rules and for rule documents
    public static final String ENTITY_HIT = "entity.hit";
    public static final String ENTITY_KILL = "entity.kill";
    public static final String PLAYER_HURT = "player.hurt";
    public static final String PLAYER_JOIN = "player.join";
    public static final String PLAYER_QUIT = "player.quit";
    public static final String PLAYER_RESPAWN = "player.respawn";
    // the player dying is the existing DEATH trigger: an item fires it per stack, a rule document once

    private EventTypes() {}
}
