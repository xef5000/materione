package dev.materione.plugin.logic.item;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.ItemSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * {@code materione:player_slot}: the viewer's own item in one of their hand or armour slots (logic.md §4.4):
 * {@code player_slot: main_hand}. An empty slot shows nothing; it is not a barrier. The stack is a copy, so a
 * window never holds the viewer's item.
 */
public final class PlayerSlotItemSource implements ItemSource<PlayerSlotItemSource.Slot> {

    /** The slots a viewer's item can come from. */
    public enum Slot { MAIN_HAND, OFF_HAND, HELMET, CHESTPLATE, LEGGINGS, BOOTS }

    @Override
    public String id() {
        return "player_slot";
    }

    @Override
    public ItemSourceDescription describe() {
        return ItemSourceDescription.of(new SelfDescription(
                "Player slot",
                "The viewer's own item in a hand or armour slot, shown as it is",
                List.of(new ParameterSchema("slot", ParameterType.ENUM, true, null,
                        "Which of the viewer's slots to show", List.of("main_hand", "off_hand", "helmet",
                        "chestplate", "leggings", "boots"), null)),
                "1.0.0"),
                "slot");
    }

    @Override
    public Slot parse(ParamReader params) {
        String text = params.text("slot").trim().toUpperCase(Locale.ROOT);
        try {
            return Slot.valueOf(text);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("'" + params.text("slot") + "' is not a player slot: main_hand, "
                    + "off_hand, helmet, chestplate, leggings or boots");
        }
    }

    @Override
    public ItemResolution resolve(Slot slot, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ItemResolution.unresolved("the viewer is not online");
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = switch (slot) {
            case MAIN_HAND -> inventory.getItemInMainHand();
            case OFF_HAND -> inventory.getItemInOffHand();
            case HELMET -> inventory.getHelmet();
            case CHESTPLATE -> inventory.getChestplate();
            case LEGGINGS -> inventory.getLeggings();
            case BOOTS -> inventory.getBoots();
        };
        return stack == null || stack.getType().isAir() ? ItemResolution.none() : ItemResolution.ok(stack.clone());
    }
}
