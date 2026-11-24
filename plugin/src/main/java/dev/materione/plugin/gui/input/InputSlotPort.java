package dev.materione.plugin.gui.input;

import dev.materione.plugin.view.ViewerRecord;
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
    StackView peek(ViewerRecord session, int slot);

    /**
     * Applies an operation to the item in an input slot.
     *
     * @param player the viewer, who receives what {@link SlotOp.Kind#TAKE} takes out
     * @return empty when the operation was applied, or the reason it was not
     */
    java.util.Optional<String> apply(Player player, ViewerRecord session, int slot, SlotOp op);

    /**
     * The custody consume primitive (#248; gui-input-durability.md §5.4): consumes the stack a {@code durable: required}
     * input slot holds, atomically, through the tier-2 exit, and completes when it reached {@code reach}. Only a slot
     * whose layer is {@code durable: required} is in custody; any other slot is refused with the reason. Call on the
     * viewer's thread.
     *
     * @return a stage that completes empty when the consume reached {@code reach}, or with the reason it did not
     */
    java.util.concurrent.CompletionStage<java.util.Optional<String>> consume(
            Player player, ViewerRecord session, int slot,
            dev.materione.plugin.escrow.runtime.CustodyConsume.Reach reach);

    /** A universal joint transfer, resolved either from a held slot or an exact holder/entry. */
    default java.util.concurrent.CompletionStage<java.util.Optional<String>> transfer(
            Player player, ViewerRecord session, Transfer transfer) {
        return java.util.concurrent.CompletableFuture.completedFuture(
                java.util.Optional.of("custody transfer is unavailable"));
    }

    /** Resolved action data; a slot source may fill the holder/entry from the open window. */
    record Transfer(
            dev.materione.plugin.escrow.model.TransferHolder sourceHolder,
            Integer sourceSlot,
            dev.materione.plugin.escrow.model.Id128 sourceEntry,
            dev.materione.plugin.escrow.model.TransferHolder destinationHolder,
            int destinationSlot,
            dev.materione.plugin.escrow.model.Id128 transaction,
            dev.materione.plugin.escrow.runtime.CustodyTransfer.Reach reach) {}
}
