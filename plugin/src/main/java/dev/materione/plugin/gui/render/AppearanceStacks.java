package dev.materione.plugin.gui.render;

import dev.materione.platform.items.MaterioneStacks;
import dev.materione.platform.text.ItemText;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Builds the stack a window slot shows from its {@link Appearance} (logic.md §6.3): the source's item, then
 * the overrides. The only place a render becomes Bukkit stacks, and it runs only for a slot that changed.
 */
public final class AppearanceStacks {

    private AppearanceStacks() {}

    /** The stack for one appearance, a fresh object the caller owns. */
    public static ItemStack build(Appearance appearance) {
        ItemStack stack = base(appearance.source());
        stack.setAmount(Math.max(1, Math.min(appearance.amount(), stack.getMaxStackSize())));
        stack.editMeta(meta -> overrides(meta, appearance));
        return stack;
    }

    private static ItemStack base(SlotSource source) {
        return switch (source) {
            case SlotSource.Materione materione -> MaterioneStacks.build(MaterioneStacks.specOf(materione.item()));
            case SlotSource.Vanilla vanilla -> new ItemStack(vanilla.material());
            case SlotSource.Stack stack -> stack.stack().clone();
            case SlotSource.Barrier barrier -> barrier(barrier.reason());
        };
    }

    /** A barrier: a red barrier block whose lore is the reason. Plain text, so the reason cannot inject tags. */
    private static ItemStack barrier(String reason) {
        ItemStack stack = new ItemStack(Material.BARRIER);
        stack.editMeta(meta -> {
            meta.displayName(plain("Unavailable", NamedTextColor.RED));
            meta.lore(List.of(plain(reason, NamedTextColor.GRAY)));
        });
        return stack;
    }

    private static void overrides(ItemMeta meta, Appearance appearance) {
        if (appearance.name() != null) {
            meta.displayName(ItemText.text(appearance.name()));
        }
        if (!appearance.lore().isEmpty()) {
            var added = new ArrayList<Component>(appearance.lore().size());
            for (String line : appearance.lore()) {
                added.add(ItemText.text(line));
            }
            List<Component> own = meta.lore() == null ? List.of() : meta.lore();
            meta.lore(switch (appearance.loreMode()) {
                case REPLACE -> added;
                case APPEND -> concat(own, added);
                case PREPEND -> concat(added, own);
            });
        }
        if (appearance.glint() != null) {
            meta.setEnchantmentGlintOverride(appearance.glint());
        }
    }

    private static List<Component> concat(List<Component> first, List<Component> second) {
        var out = new ArrayList<Component>(first.size() + second.size());
        out.addAll(first);
        out.addAll(second);
        return out;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
