package dev.materione.plugin.logic.support;

import dev.materione.plugin.gui.input.StackView;
import dev.materione.plugin.gui.session.GuiSession;

/**
 * The port {@code slot_has} reads (logic.md §25): the item an input slot of a GUI session's window holds. It is
 * the same shape as {@link OpenGuis}: {@code BuiltinLogic} backs it with the GUI runtime through the
 * {@code GuiBridge}, and {@link #none()} answers "empty" for tests and hosts without that runtime, so the
 * condition is false and never throws.
 *
 * <p>A condition runs on the player's thread, so an implementation may read the window directly.
 */
@FunctionalInterface
public interface SlotPeek {

    /**
     * @return the view of the item in {@code slot}, {@link StackView#EMPTY} when the slot is empty, is not an input
     *     slot of the session's GUI, or the window is gone
     */
    StackView peek(GuiSession session, int slot);

    /** The port with no GUI runtime behind it: every slot is empty. */
    static SlotPeek none() {
        return (session, slot) -> StackView.EMPTY;
    }
}
