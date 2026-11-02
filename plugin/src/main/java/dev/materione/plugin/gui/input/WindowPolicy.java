package dev.materione.plugin.gui.input;

/**
 * What a window's clicks are judged against (logic.md §25): the input slots and lock of its displayed
 * definition, or the fact that the window is no longer the player's.
 *
 * @param menu whether the window is a {@code GuiMenu} (a GUI session's window); false for the older
 *     GUIs, which keep the behaviour they had before input slots
 * @param live whether the session behind the window is open, current and has a displayed definition
 * @param slots the displayed input slots and lock; {@link InputSlots#NONE} otherwise
 */
public record WindowPolicy(boolean menu, boolean live, InputSlots slots) {

    /** The window of a GUI that is not a session's (the older, non-input GUIs). */
    public static final WindowPolicy LEGACY = new WindowPolicy(false, false, InputSlots.NONE);

    /** A session's window that is no longer its player's live window: every click on it is refused. */
    public static final WindowPolicy STALE = new WindowPolicy(true, false, InputSlots.NONE);

    public static WindowPolicy live(InputSlots slots) {
        return new WindowPolicy(true, true, slots);
    }
}
