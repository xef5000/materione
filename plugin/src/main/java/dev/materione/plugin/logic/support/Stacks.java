package dev.materione.plugin.logic.support;

import dev.materione.platform.items.MaterioneStacks;
import dev.materione.plugin.logic.support.InventoryPlan.Take;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The Bukkit edge of the item actions: reads an inventory into {@link ItemView}s and applies an
 * {@link InventoryPlan}. Needs a server (stacks and their metadata), so it is covered by the QA
 * suite, not by unit tests; everything it decides lives in the pure classes beside it.
 */
public final class Stacks {

    private Stacks() {}

    /** Every slot of {@code inventory}; null for an empty slot. */
    public static List<ItemView> contents(Inventory inventory) {
        ItemStack[] stacks = inventory.getContents();
        var views = new ArrayList<ItemView>(stacks.length);
        for (ItemStack stack : stacks) {
            views.add(stack == null || stack.getType().isAir() ? null : view(stack));
        }
        return views;
    }

    /** The matching fields of one stack. */
    public static ItemView view(ItemStack stack) {
        Optional<String> name = stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()
                ? Optional.of(PlainTextComponentSerializer.plainText()
                        .serialize(stack.getItemMeta().displayName()))
                : Optional.empty();
        List<String> lore = stack.hasItemMeta() && stack.getItemMeta().hasLore()
                ? stack.getItemMeta().lore().stream()
                        .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                        .toList()
                : List.of();
        return new ItemView(MaterioneStacks.identityOf(stack), stack.getType().name(), name, stack.getAmount(), lore);
    }

    /** Removes the planned amounts. Call only with a plan the same contents produced. */
    public static void apply(Inventory inventory, List<Take> takes) {
        for (Take take : takes) {
            ItemStack stack = inventory.getItem(take.slot());
            if (stack == null) {
                continue;
            }
            if (stack.getAmount() <= take.amount()) {
                inventory.setItem(take.slot(), null);
            } else {
                stack.setAmount(stack.getAmount() - take.amount());
                inventory.setItem(take.slot(), stack);
            }
        }
    }
}
