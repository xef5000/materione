package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Optional;
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

    private final GuiLookup guis;
    private final GuiJournal journal;

    public InputSlotOps(GuiLookup guis, GuiJournal journal) {
        this.guis = guis;
        this.journal = journal;
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
        ItemStack item = inventory.getItem(slot);
        if (BukkitStacks.view(item).empty()) {
            return Optional.of("slot " + slot + " is empty");
        }
        switch (op.kind()) {
            case CLEAR -> inventory.setItem(slot, null);
            case TAKE -> {
                inventory.setItem(slot, null);
                InputReturns.giveBack(player, item);
            }
            case SET_AMOUNT -> {
                if (op.amount() > item.getMaxStackSize()) {
                    return Optional.of("the stack holds at most " + item.getMaxStackSize());
                }
                inventory.setItem(slot, BukkitStacks.withAmount(item, op.amount()));
            }
            case REPAIR -> {
                if (!(item.getItemMeta() instanceof Damageable damageable)) {
                    return Optional.of("the item cannot be repaired");
                }
                damageable.setDamage(0);
                item.setItemMeta(damageable);
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
                inventory.setItem(slot, item);
            }
            default -> {
                return Optional.of("unsupported operation " + op.kind().id());
            }
        }
        journal.mirror(player); // the window changed: the journal follows it
        return Optional.empty();
    }

    /** The window's inventory, when {@code slot} is an input slot of the session's live GUI and its window is showing. */
    private Optional<Inventory> inputInventory(GuiSession session, int slot) {
        if (!session.isOpen() || !(session.window() instanceof GuiMenu menu)) {
            return Optional.empty();
        }
        Optional<RuntimeGuis.Live> live = guis.find(session.gui());
        if (live.isEmpty() || !live.get().inputs().isInput(slot) || !menu.inputSlots().contains(slot)) {
            return Optional.empty();
        }
        return Optional.of(menu.getInventory());
    }
}
