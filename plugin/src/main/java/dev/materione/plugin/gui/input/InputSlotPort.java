package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.session.GuiSession;
import org.bukkit.entity.Player;

/**
 * What the logic actions and conditions reach of a window's input slots (logic.md §25): read an item, and
 * apply a {@link SlotOp}. The session's window is the only window they touch; a slot that is not an input
 * slot of the session's live GUI is never read or changed.
 *
 * <p>Implemented over Bukkit by {@link InputSlotOps}; the condition and the action are tested against a fake.
 */
public interface InputSlotPort {

    /**
     * The item an input slot of the session's window holds.
     *
     * @return the view, {@link StackView#EMPTY} when the slot is empty, not an input slot, or the window is gone
     */
    StackView peek(GuiSession session, int slot);

    /**
     * Applies an operation to the item in an input slot.
     *
     * @param player the viewer, who receives what {@link SlotOp.Kind#TAKE} takes out
     * @return empty when the operation was applied, or the reason it was not
     */
    java.util.Optional<String> apply(Player player, GuiSession session, int slot, SlotOp op);
}
