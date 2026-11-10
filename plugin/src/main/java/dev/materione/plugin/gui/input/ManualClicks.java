package dev.materione.plugin.gui.input;

import dev.materione.plugin.escrow.runtime.ClickPlanner;
import dev.materione.plugin.escrow.runtime.Delta;
import dev.materione.plugin.escrow.runtime.ItemStacks;
import dev.materione.plugin.escrow.runtime.Live;
import dev.materione.plugin.gui.GuiMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The clicks the plugin applies itself on a window that has {@code durable: required} slots (gui-input-durability.md
 * §5.5): the event is cancelled and the whole result, the slot, the cursor and any hotbar or offhand slot, is built
 * here and applied in one tier-1 step by {@link StrictInputs#apply}. The server never applies a click that changes an
 * escrowed slot, so no mirror is ever one tick late (W1, K19).
 *
 * <p>The check (the slot's {@code accept:}, {@code gui.slot_change}) has already run in {@link InputSlotClicks} on the
 * server's own prediction; this class only produces what the server would have produced, by the same rules
 * ({@link ClickPlanner}).
 */
final class ManualClicks {

    private final StrictInputs strict;
    private final ClickPlanner<ItemStack> planner = new ClickPlanner<>(ItemStacks.INSTANCE);

    ManualClicks(StrictInputs strict) {
        this.strict = strict;
    }

    /** One single-slot click on a required input slot: the planner's outcome, applied in one step. */
    void click(Player player, GuiMenu menu, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        int bagIndex = -1;
        ItemStack other = null;
        if (event.getAction() == InventoryAction.HOTBAR_SWAP) {
            int button = event.getHotbarButton();
            bagIndex = button == SlotPolicy.OFFHAND_BUTTON ? Live.OFFHAND : button >= 0 && button <= 8 ? button : -1;
            if (bagIndex < 0) {
                return;
            }
            other = player.getInventory().getItem(bagIndex);
        }
        var outcome = planner.plan(event.getAction(), menu.getInventory().getItem(slot), player.getItemOnCursor(), other);
        if (outcome.isEmpty()) {
            return; // an action with no plan for this state: the click stays cancelled, nothing changed
        }
        Delta<ItemStack> delta = new Delta<ItemStack>().top(slot, outcome.get().slot()).cursor(outcome.get().cursor());
        if (bagIndex >= 0) {
            delta.bag(bagIndex, outcome.get().other());
        }
        strict.apply(player, menu, delta);
    }

    /** A drag that touches a required slot: every slot the drag filled, and the cursor that is left, in one step. */
    void drag(Player player, GuiMenu menu, InventoryDragEvent event) {
        int topSize = menu.getInventory().getSize();
        Delta<ItemStack> delta = new Delta<>();
        for (int raw : event.getRawSlots()) {
            ItemStack placed = event.getNewItems().get(raw);
            if (raw < topSize) {
                delta.top(raw, placed);
            } else {
                delta.bag(event.getView().convertSlot(raw), placed);
            }
        }
        delta.cursor(event.getCursor());
        strict.apply(player, menu, delta);
    }
}
