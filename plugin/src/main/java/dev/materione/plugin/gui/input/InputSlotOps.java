package dev.materione.plugin.gui.input;

import dev.materione.plugin.escrow.runtime.CustodyConsume;
import dev.materione.plugin.escrow.runtime.Tier1;
import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * The Bukkit implementation of {@link InputSlotPort} (logic.md §25): reads and changes the item of a session's
 * input slot in the session's own window. Runs on the viewer's thread, as the actions that call it do.
 */
public final class InputSlotOps implements InputSlotPort {

    private final GuiJournal journal;
    private final StrictInputs strict;

    public InputSlotOps(GuiLookup guis, GuiJournal journal) {
        this(guis, journal, StrictInputs.NONE);
    }

    /** @param strict the escrow path of {@code durable: required} slots (gui-input-durability.md §5.5, row P of #247) */
    public InputSlotOps(GuiLookup guis, GuiJournal journal, StrictInputs strict) {
        this.journal = journal;
        this.strict = strict;
    }

    @Override
    public StackView peek(GuiSession session, int slot) {
        Optional<Inventory> top = inputInventory(session, slot);
        return top.map(inventory -> BukkitStacks.view(inventory.getItem(slot))).orElse(StackView.EMPTY);
    }

    @Override
    public Optional<String> apply(Player player, GuiSession session, int slot, SlotOp op) {
        Optional<Inventory> top = inputInventory(session, slot);
        if (top.isEmpty()) {
            return Optional.of("slot " + slot + " is not an input slot of an open GUI");
        }
        Inventory inventory = top.get();
        GuiMenu menu = (GuiMenu) session.window();
        boolean escrowed = strict.escrowed(menu, slot);
        // an escrowed slot is edited on a copy: the live stack changes only inside the tier-1 step, with the account
        ItemStack item = escrowed && inventory.getItem(slot) != null
                ? inventory.getItem(slot).clone() : inventory.getItem(slot);
        if (BukkitStacks.view(item).empty()) {
            return Optional.of("slot " + slot + " is empty");
        }
        if (escrowed && !strict.usable(player, menu, slot)) {
            strict.refuse(player, Tier1.Reason.UNAVAILABLE);
            return Optional.of("crash-safe input slots are not available for this player right now");
        }
        switch (op.kind()) {
            case CLEAR -> {
                if (escrowed) {
                    // a deletion of an escrowed stack is a tier-2 exit (its decision must outlive a rollback): the same
                    // consume as custody.consume, started here and not waited for (#248)
                    return strict.clear(player, menu, slot);
                }
                inventory.setItem(slot, null);
            }
            case TAKE -> {
                if (escrowed) {
                    return strict.take(player, menu, slot);
                }
                inventory.setItem(slot, null);
                InputReturns.giveBack(player, item);
            }
            case SET_AMOUNT -> {
                if (op.amount() > item.getMaxStackSize()) {
                    return Optional.of("the stack holds at most " + item.getMaxStackSize());
                }
                if (escrowed) {
                    // set_amount 0 deletes the stack: the same tier-2 consume as clear; any other amount is an edit
                    return op.amount() <= 0
                            ? strict.clear(player, menu, slot)
                            : strict.edit(player, menu, slot, BukkitStacks.withAmount(item, op.amount()));
                }
                inventory.setItem(slot, BukkitStacks.withAmount(item, op.amount()));
            }
            case REPAIR -> {
                if (!(item.getItemMeta() instanceof Damageable damageable)) {
                    return Optional.of("the item cannot be repaired");
                }
                damageable.setDamage(0);
                item.setItemMeta(damageable);
                if (escrowed) {
                    return strict.edit(player, menu, slot, item);
                }
                inventory.setItem(slot, item);
            }
            case ENCHANT -> {
                Enchantment enchantment = op.enchantment().isBlank() ? null
                        : io.papermc.paper.registry.RegistryAccess.registryAccess()
                                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                                .get(NamespacedKey.minecraft(op.enchantment().toLowerCase(java.util.Locale.ROOT)));
                if (enchantment == null) {
                    return Optional.of("unknown enchantment '" + op.enchantment() + "'");
                }
                if (op.level() < 1) {
                    return Optional.of("an enchantment level is at least 1");
                }
                item.addUnsafeEnchantment(enchantment, op.level());
                if (escrowed) {
                    return strict.edit(player, menu, slot, item);
                }
                inventory.setItem(slot, item);
            }
            default -> {
                return Optional.of("unsupported operation " + op.kind().id());
            }
        }
        journal.mirror(player); // the window changed: the journal follows it
        return Optional.empty();
    }

    @Override
    public CompletionStage<Optional<String>> consume(Player player, GuiSession session, int slot,
            CustodyConsume.Reach reach) {
        Optional<Inventory> top = inputInventory(session, slot);
        if (top.isEmpty()) {
            return refused("slot " + slot + " is not an input slot of an open GUI");
        }
        GuiMenu menu = (GuiMenu) session.window();
        if (!strict.escrowed(menu, slot)) {
            return refused("slot " + slot + " is not held in custody: its layer is durable: best_effort (a consume "
                    + "needs durable: required; gui.slot_item clear deletes from a best_effort layer)");
        }
        if (BukkitStacks.view(top.get().getItem(slot)).empty()) {
            return refused("slot " + slot + " is empty");
        }
        return strict.consume(player, menu, slot, reach);
    }

    private static CompletionStage<Optional<String>> refused(String reason) {
        return CompletableFuture.completedFuture(Optional.of(reason));
    }

    /** The window's inventory, when {@code slot} belongs to its displayed input policy. */
    private Optional<Inventory> inputInventory(GuiSession session, int slot) {
        if (!session.isOpen() || !(session.window() instanceof GuiMenu menu)) {
            return Optional.empty();
        }
        Optional<RuntimeGuis.Live> live = session.displayed();
        if (live.isEmpty() || !live.get().inputs().isInput(slot) || !menu.inputSlots().contains(slot)) {
            return Optional.empty();
        }
        return Optional.of(menu.getInventory());
    }
}
