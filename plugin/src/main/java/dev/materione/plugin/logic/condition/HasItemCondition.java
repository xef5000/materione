package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.items.Give;
import dev.materione.plugin.logic.support.InventoryPlan;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.ItemMatch;
import dev.materione.plugin.logic.support.ItemRef;
import dev.materione.plugin.logic.support.ItemSlots;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stacks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.materione.plugin.logic.support.ResolvedText;
import org.bukkit.entity.Player;

/**
 * {@code has_item: ruby} or {@code has_item: {item, amount, name_contains, lore_contains, ignore_case,
 * strict, armor, offhand}} (logic.md §22). It counts with the matcher {@code take_item} uses
 * ({@link ItemMatch}, in {@code plugin.logic.support}), so the two agree on what an item is.
 *
 * <ul>
 *   <li>{@code item}: a Materione id, or {@code minecraft:<material>}.
 *   <li>{@code amount}: how many must be held, across stacks (default 1).
 *   <li>{@code name_contains} and {@code lore_contains}: text some display name or lore line must contain.
 *   <li>{@code ignore_case} (default true, as for {@code take_item}): whether that text compares without case.
 *   <li>{@code strict} (default true): a vanilla reference matches only stacks with no Materione identity.
 *       {@code strict: false} lets {@code minecraft:paper} count a custom item shaped like paper.
 *   <li>{@code armor} and {@code offhand} (default true): whether the armour and offhand slots count.
 * </ul>
 *
 * <p>A condition runs on the player's thread (logic.md §16, decision 17), which is the thread the inventory
 * may be read on. An item the server cannot resolve is false, not an error.
 */
public final class HasItemCondition implements ConditionType<HasItemCondition.Params> {

    /** The parsed condition. The optional texts are templates, rendered at test time. */
    public record Params(TemplateText item, Optional<TemplateText> nameContains, Optional<TemplateText> loreContains,
            Quantity amount, boolean ignoreCase, boolean strict, boolean armour, boolean offhand) {}

    private final ItemCatalog catalog;

    /** @param catalog the live item registries, read each time the condition is tested */
    public HasItemCondition(ItemCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String id() {
        return "has_item";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Has item",
                "True when the player holds at least the amount of an item, matching by id or material, name and lore",
                List.of(
                        new ParameterSchema("item", ParameterType.STRING, true, null,
                                "Materione item id, or minecraft:<material>", null, null),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "How many must be held, across stacks", null, null),
                        new ParameterSchema("name_contains", ParameterType.STRING, false, null,
                                "Only items whose display name contains this text", null, null),
                        new ParameterSchema("lore_contains", ParameterType.STRING, false, null,
                                "Only items with a lore line containing this text", null, null),
                        new ParameterSchema("ignore_case", ParameterType.BOOLEAN, false, "true",
                                "Compare the name and lore text without case", null, null),
                        new ParameterSchema("strict", ParameterType.BOOLEAN, false, "true",
                                "A vanilla reference skips stacks that carry a Materione identity", null, null),
                        new ParameterSchema("armor", ParameterType.BOOLEAN, false, "true",
                                "Count the armour slots", null, null),
                        new ParameterSchema("offhand", ParameterType.BOOLEAN, false, "true",
                                "Count the offhand slot", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "item");
    }

    @Override
    public Params parse(ParamReader params) {
        TemplateText item = params.template("item");
        ItemRef.checkLiteral(item.source());
        return new Params(
                item,
                params.optionalTemplate("name_contains"),
                params.optionalTemplate("lore_contains"),
                Quantity.optionalCount(params, "amount", 1, Give.MAX_AMOUNT)
                        .orElse(new Quantity(1, null, 1, Give.MAX_AMOUNT, true)),
                params.bool("ignore_case", true),
                params.bool("strict", true),
                params.bool("armor", true),
                params.bool("offhand", true));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        var whole = params.amount().wholeValue(context);
        if (whole.isEmpty()) {
            return false;
        }
        RuntimeRegistries registries = catalog.current().orElse(null);
        ItemRef ref;
        try {
            ref = ItemRef.resolve(params.item().render(context).trim(), registries);
        } catch (IllegalArgumentException e) {
            return false;
        }
        ItemMatch match = new ItemMatch(ref, nonBlank(params.nameContains(), context),
                nonBlank(params.loreContains(), context), params.ignoreCase(), params.strict());
        return has(player, params, match, whole.getAsInt());
    }

    private static boolean has(Player player, Params params, ItemMatch match, int amount) {
        var slots = ItemSlots.included(Stacks.contents(player.getInventory()), params.armour(), params.offhand());
        return InventoryPlan.count(slots, match.predicate()) >= amount;
    }

    @Override public CompletionStage<Boolean> testResumable(Params params, LogicContext context, ReadExecutor reads) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture(false);
        }
        return params.amount().resolveResumable(context, reads).thenCompose(quantity -> {
            var whole = quantity.wholeValue(context);
            if (whole.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            return reads.on(Threading.PLAYER_REGION, () -> catalog.current().orElse(null)).thenCompose(registries ->
                    params.item().renderResumable(context, reads).thenCompose(item ->
                            reads.on(Threading.PLAYER_REGION, () -> {
                                try {
                                    return ItemRef.resolve(item.trim(), registries);
                                } catch (IllegalArgumentException ignored) {
                                    return null;
                                }
                            })).thenCompose(ref -> {
                                if (ref == null) {
                                    return CompletableFuture.completedFuture(false);
                                }
                                return ResolvedText.resolve(params.nameContains(), context, reads).thenCompose(name ->
                                        ResolvedText.resolve(params.loreContains(), context, reads)
                                                .thenCompose(lore -> reads.on(Threading.PLAYER_REGION, () -> {
                                                    ItemMatch match = new ItemMatch(ref, nonBlank(name, context),
                                                            nonBlank(lore, context), params.ignoreCase(), params.strict());
                                                    return has(player, params, match, whole.getAsInt());
                                                })));
                            }));
        });
    }

    /** A rendered text, or empty when the author wrote none or it renders blank (as for {@code take_item}). */
    static Optional<String> nonBlank(Optional<TemplateText> text, LogicContext context) {
        return text.map(template -> template.render(context)).filter(rendered -> !rendered.isBlank());
    }
}
