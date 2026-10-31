package dev.materione.plugin.gui.input;

/**
 * What a window's clicks are judged against, read from the live view when the click happens (logic.md
 * §25): the input slots and the lock of the GUI the window shows, or the fact that the window is no
 * longer the player's. A reload is therefore followed by the next click, with no copy to keep in step.
 *
 * @param menu whether the window is a {@code GuiMenu} (a GUI session's window); false for the older
 *     GUIs, which keep the behaviour they had before input slots
 * @param live whether the session behind the window is open, the player's current one and its GUI is live
 * @param slots the input slots and lock of the live GUI; {@link InputSlots#NONE} otherwise
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
