package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.items.Give;
import dev.materione.plugin.logic.support.InventoryPlan;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.ItemRef;
import dev.materione.plugin.logic.support.ItemView;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stacks;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * {@code take_item: ruby} or {@code take_item: {item, amount, name_contains}}: removes items from the
 * player. All or nothing: when the player holds fewer than {@code amount} matching items the step
 * fails and removes nothing, which is what lets a {@code require} step keep a purchase atomic.
 * The matcher is the one {@code has_item} uses: a Materione id or {@code minecraft:<material>}
 * (vanilla matches only stacks with no Materione identity), and optionally a display name that
 * contains text.
 */
public final class TakeItemAction implements ActionType<TakeItemAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText item, Optional<TemplateText> nameContains, Quantity amount) {}

    private final ItemCatalog catalog;

    /** @param catalog the live item registries, read each time the step runs */
    public TakeItemAction(ItemCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String id() {
        return "take_item";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Take item",
                "Removes items from the player; fails and takes nothing when they hold too few",
                List.of(
                        new ParameterSchema("item", ParameterType.STRING, true, null,
                                "Materione item id, or minecraft:<material>", null, null),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "How many to take", null, null),
                        new ParameterSchema("name_contains", ParameterType.STRING, false, null,
                                "Only items whose display name contains this text", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "item");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        TemplateText item = params.template("item");
        ItemRef.checkLiteral(item.source());
        return new Params(
                item,
                params.optionalTemplate("name_contains"),
                Quantity.requiredCount(params, "amount", 1, Give.MAX_AMOUNT));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        var whole = params.amount().wholeValue(context);
        if (whole.isEmpty() || whole.getAsInt() < 1 || whole.getAsInt() > Give.MAX_AMOUNT) {
            return ActionOutcome.failed("amount must be a whole number from 1 to " + Give.MAX_AMOUNT);
        }
        int amount = whole.getAsInt();
        String rendered = params.item().render(context).trim();
        RuntimeRegistries registries = catalog.current().orElse(null);
        ItemRef ref;
        try {
            ref = ItemRef.resolve(rendered, registries);
        } catch (IllegalArgumentException e) {
            return ActionOutcome.failed(e.getMessage());
        }
        Optional<String> needle = params.nameContains()
                .map(text -> text.render(context))
                .filter(text -> !text.isBlank());
        Predicate<ItemView> wanted = InventoryPlan.wanted(ref, needle);
        List<ItemView> slots = Stacks.contents(player.getInventory());
        var plan = InventoryPlan.plan(slots, wanted, amount);
        if (plan.isEmpty()) {
            int held = InventoryPlan.count(slots, wanted);
            return ActionOutcome.failed("player holds " + held + " of '" + rendered + "', needs " + amount);
        }
        Stacks.apply(player.getInventory(), plan.get());
        return ActionOutcome.OK;
    }
}
