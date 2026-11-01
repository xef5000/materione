package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.items.Give;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.ItemRef;
import dev.materione.plugin.logic.support.ItemTemplates;
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code give_item: ruby} or {@code give_item: {item, amount}}: gives the player items. {@code item}
 * is a live Materione item (the {@code /materione give} spelling) or {@code minecraft:<material>}.
 * What does not fit drops at the player's feet, as {@code /materione give} does.
 */
public final class GiveItemAction implements ActionType<GiveItemAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText item, Optional<Quantity> amount) {}

    private final ItemCatalog catalog;

    /** @param catalog the live item registries, read each time the step runs */
    public GiveItemAction(ItemCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String id() {
        return "give_item";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Give item",
                "Gives the player a Materione item or a vanilla item; overflow drops at their feet",
                List.of(
                        new ParameterSchema("item", ParameterType.STRING, true, null,
                                "Materione item id, or minecraft:<material>", null, null),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "How many", null, null)),
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
        return new Params(item, Quantity.optionalCount(params, "amount", 1, Give.MAX_AMOUNT));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        int amount = 1;
        if (params.amount().isPresent()) {
            var whole = params.amount().get().wholeValue(context);
            if (whole.isEmpty() || whole.getAsInt() < 1 || whole.getAsInt() > Give.MAX_AMOUNT) {
                return ActionOutcome.failed("amount must be a whole number from 1 to " + Give.MAX_AMOUNT);
            }
            amount = whole.getAsInt();
        }
        RuntimeRegistries registries = catalog.current().orElse(null);
        try {
            return deliver(player, amount, registries, params.item().render(context));
        } catch (IllegalArgumentException failure) {
            return ActionOutcome.failed(failure.getMessage());
        }
    }

    private ActionOutcome deliver(Player player, int amount, RuntimeRegistries registries, String item) {
        ItemStack template;
        try {
            template = ItemTemplates.of(ItemRef.resolve(item, registries), registries);
        } catch (IllegalArgumentException e) {
            return ActionOutcome.failed(e.getMessage());
        }
        Give.deliver(player, template, amount);
        return ActionOutcome.OK;
    }

    @Override public CompletionStage<ActionOutcome> executeResumable(
            Params params, LogicContext context, ReadExecutor reads) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture(ActionOutcome.failed("event has no player"));
        }
        CompletionStage<Optional<Quantity>> amount = params.amount().isEmpty()
                ? CompletableFuture.completedFuture(Optional.empty())
                : params.amount().get().resolveResumable(context, reads).thenApply(Optional::of);
        return amount.thenCompose(resolved -> {
            int count = 1;
            if (resolved.isPresent()) {
                var whole = resolved.get().wholeValue(context);
                if (whole.isEmpty() || whole.getAsInt() < 1 || whole.getAsInt() > Give.MAX_AMOUNT) {
                    return CompletableFuture.completedFuture(
                            ActionOutcome.failed("amount must be a whole number from 1 to " + Give.MAX_AMOUNT));
                }
                count = whole.getAsInt();
            }
            int finalCount = count;
            return reads.on(threading(), () -> catalog.current().orElse(null)).thenCompose(registries ->
                    params.item().renderResumable(context, reads).thenCompose(item ->
                            reads.on(threading(), () -> deliver(player, finalCount, registries, item))));
        });
    }
}
