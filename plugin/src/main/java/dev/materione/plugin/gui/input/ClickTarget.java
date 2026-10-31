package dev.materione.plugin.gui.input;

/**
 * Where a click on a window landed, for the click policy table (logic.md §6.5, gui.md §5). The listener
 * decides it from the window's {@link WindowPolicy} and the clicked slot; the table in {@code GuiListener}
 * decides what happens to a click at each target.
 */
public enum ClickTarget {

    /** Outside the window. */
    OUTSIDE_WINDOW,
    /** One of the GUI's own slots that is not an input slot: display, buttons and lists. */
    GUI_TOP,
    /** An input slot of a live GUI: the player's items may go in and out. */
    INPUT_SLOT,
    /** The viewer's own inventory, in a window with no input slots and a free inventory. */
    VIEWER,
    /** The viewer's own inventory, in a window with input slots and a free inventory. */
    VIEWER_INPUTS,
    /** The viewer's own inventory, in a window whose player inventory is locked, or a stale window. */
    VIEWER_LOCKED;

    /** The target of a click on the window: the clicked inventory is the window's own ({@code top}) or the viewer's. */
    public static ClickTarget of(WindowPolicy policy, boolean outside, boolean top, int slot) {
        if (outside) {
            return OUTSIDE_WINDOW;
        }
        if (!policy.menu() || !policy.live()) {
            return top ? GUI_TOP : (policy.menu() ? VIEWER_LOCKED : VIEWER);
        }
        if (top) {
            return policy.slots().isInput(slot) ? INPUT_SLOT : GUI_TOP;
        }
        if (policy.slots().locked()) {
            return VIEWER_LOCKED;
        }
        return policy.slots().hasInputs() ? VIEWER_INPUTS : VIEWER;
    }

    /** Whether the click was on the viewer's own inventory (whichever way it is judged). */
    public boolean viewer() {
        return this == VIEWER || this == VIEWER_INPUTS || this == VIEWER_LOCKED;
    }
}
