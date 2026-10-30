package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.items.Give;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.ItemMatch;
import dev.materione.plugin.logic.support.ItemRef;
import dev.materione.plugin.logic.support.ItemSlots;
import dev.materione.plugin.logic.support.ItemTemplates;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stacks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code has_item_space: 3} (three free slots) or {@code has_item_space: {item: ruby, amount: 5}} (room for
 * five rubies) (logic.md §19). Exactly one of {@code slots} and {@code item} is written.
 *
 * <p>Room counts the main storage only (slots {@code 0..35}), the slots a pickup fills; the armour and
 * offhand are never room. An empty slot holds a whole stack of the item, and a matching partial stack holds
 * the rest of its own (the maximum stack size comes from the item's built stack). An unresolved item is
 * false.
 */
public final class HasItemSpaceCondition implements ConditionType<HasItemSpaceCondition.Params> {

    /**
     * The parsed condition: exactly one of {@code slots} (free storage slots) or {@code item} with
     * {@code amount} (room for that many of it).
     */
    public record Params(Optional<Quantity> slots, Optional<TemplateText> item, Quantity amount) {}

    private final ItemCatalog catalog;

    /** @param catalog the live item registries, read each time the condition is tested */
    public HasItemSpaceCondition(ItemCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String id() {
        return "has_item_space";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Has item space",
                "True when the inventory has that many free slots, or room for that many of an item",
                List.of(
                        new ParameterSchema("slots", ParameterType.INTEGER, false, null,
                                "How many empty storage slots (0 to 36)", null, null),
                        new ParameterSchema("item", ParameterType.STRING, false, null,
                                "Materione item id, or minecraft:<material>; room for amount of it", null, null),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "With item: how many of it there must be room for", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "slots");
    }

    @Override
    public Params parse(ParamReader params) {
        boolean slots = params.raw("slots").isPresent();
        boolean item = params.raw("item").isPresent();
        if (slots == item) {
            throw new IllegalArgumentException(slots
                    ? "give slots or item, not both"
                    : "give slots (free slots) or item (room for an item)");
        }
        if (slots) {
            if (params.raw("amount").isPresent()) {
                throw new IllegalArgumentException("amount goes with item, not with slots");
            }
            return new Params(Optional.of(Quantity.requiredCount(params, "slots", 0, ItemSlots.STORAGE_TO)),
                    Optional.empty(), new Quantity(1, null, 1, Give.MAX_AMOUNT, true));
        }
        TemplateText template = params.template("item");
        ItemRef.checkLiteral(template.source());
        return new Params(Optional.empty(), Optional.of(template),
                Quantity.optionalCount(params, "amount", 1, Give.MAX_AMOUNT)
                        .orElse(new Quantity(1, null, 1, Give.MAX_AMOUNT, true)));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        var storage = ItemSlots.storage(Stacks.contents(player.getInventory()));
        if (params.slots().isPresent()) {
            var want = params.slots().get().wholeValue(context);
            return want.isPresent() && ItemSlots.freeSlots(storage) >= want.getAsInt();
        }
        var want = params.amount().wholeValue(context);
        if (want.isEmpty() || params.item().isEmpty()) {
            return false;
        }
        RuntimeRegistries registries = catalog.current().orElse(null);
        ItemRef ref;
        ItemStack template;
        try {
            ref = ItemRef.resolve(params.item().get().render(context).trim(), registries);
            template = ItemTemplates.of(ref, registries);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long room = ItemSlots.room(storage, ItemMatch.of(ref, Optional.empty()).predicate(),
                template.getMaxStackSize());
        return room >= want.getAsInt();
    }
}
