package dev.materione.plugin.logic.support;

import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * One stack of what an {@link ItemRef} names, built the way {@code give_item} builds it (the Bukkit edge of
 * the item actions and conditions). {@code has_item_space} reads its maximum stack size from it. Needs a
 * server, so it is covered by the QA suite.
 */
public final class ItemTemplates {

    private ItemTemplates() {}

    /**
     * One stack of {@code ref}, built with the same factory the content browser uses.
     *
     * @throws IllegalArgumentException when the vanilla name is not an item, or the Materione item was unloaded
     */
    public static ItemStack of(ItemRef ref, RuntimeRegistries registries) {
        return switch (ref) {
            case ItemRef.Vanilla vanilla -> {
                Material material = Material.matchMaterial(vanilla.material());
                if (material == null || !material.isItem()) {
                    throw new IllegalArgumentException("'minecraft:" + vanilla.material().toLowerCase() + "' is not a vanilla item");
                }
                yield new ItemStack(material);
            }
            case ItemRef.Materione item -> {
                var live = registries.item(item.id())
                        .orElseThrow(() -> new IllegalArgumentException("the item was unloaded while the step ran"));
                yield MaterioneStacks.build(MaterioneStacks.specOf(live));
            }
        };
    }
}
