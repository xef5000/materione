package dev.materione.plugin.gui.render;

import dev.materione.content.gui.ItemComponent;
import java.util.List;
import java.util.Objects;

/**
 * What one slot shows, decided and not yet built (logic.md §6.3): the source, and the templated overrides
 * already rendered for the viewer. Two appearances are equal when a viewer would see the same item, so the
 * window writes only the slots whose appearance changed ({@link RenderDiff}).
 *
 * @param source what the item is built from
 * @param name the rendered display name (MiniMessage), or null to keep the source's own name
 * @param lore the rendered lore lines (MiniMessage); empty to keep the source's own lore
 * @param loreMode how the lore lines combine with the source's own lore
 * @param amount how many, 1 to 64
 * @param glint the enchantment glint override, or null to keep the source's own
 */
public record Appearance(
        SlotSource source, String name, List<String> lore, ItemComponent.LoreMode loreMode, int amount,
        Boolean glint) {

    public Appearance {
        Objects.requireNonNull(source, "source");
        lore = List.copyOf(lore);
        loreMode = loreMode == null ? ItemComponent.LoreMode.REPLACE : loreMode;
        if (amount < 1 || amount > ItemComponent.MAX_AMOUNT) {
            throw new IllegalArgumentException("amount is 1 to " + ItemComponent.MAX_AMOUNT + ", not " + amount);
        }
    }

    /** An appearance with no overrides. */
    public static Appearance of(SlotSource source, int amount) {
        return new Appearance(source, null, List.of(), ItemComponent.LoreMode.REPLACE, amount, null);
    }
}
