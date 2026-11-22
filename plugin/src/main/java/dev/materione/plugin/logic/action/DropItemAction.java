package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
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
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.world.LocationSpec;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * {@code drop_item: ruby} or {@code drop_item: {item: ruby, amount: 3, at: player}}: drops items into the world
 * (logic.md §41). {@code item} is a live Materione item or {@code minecraft:<material>}, as for {@code give_item};
 * {@code amount} is a count (an expression is allowed); {@code at} is where they appear (default: the event's location, so
 * an item's {@code break} rule drops at the broken block). The items fall as a player's drop would, with the small random
 * offset that keeps stacks from merging into one point, and are split into stacks of the item's maximum size.
 *
 * <p>Fails when the item is unknown or was unloaded, and when the location cannot be read. <b>Budget.</b> A run drops at
 * most {@link WorldVerbs#MAX_DROP_STACKS_PER_RUN} stacks ({@code MTR-0118}); a step at most {@link Give#MAX_AMOUNT} items.
 */
public final class DropItemAction implements ActionType<DropItemAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText item, Optional<Quantity> amount, LocationSpec at) {}

    private final ItemCatalog catalog;
    private final WorldVerbs verbs;

    /**
     * @param catalog the live item registries, read each time the step runs
     * @param verbs the platform: the region seam, the world lookup and the budget reporter
     */
    public DropItemAction(ItemCatalog catalog, WorldVerbs verbs) {
        this.catalog = catalog;
        this.verbs = verbs;
    }

    @Override
    public String id() {
        return "drop_item";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Drop item",
                "Drops items into the world at a location",
                List.of(
                        new ParameterSchema("item", ParameterType.STRING, true, null,
                                "Materione item id, or minecraft:<material>", null, null).acceptingExpression()
                                .withWidget("item", ""),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "How many", null, null).acceptingExpression(),
                        new ParameterSchema("at", ParameterType.RAW, false, "event",
                                "Where: event, player, or {of, x, y, z} with a world-axis offset in blocks", null, null)),
                "2.0.0"), LogicCategory.WORLD, "item");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        TemplateText item = params.template("item");
        ItemRef.checkLiteral(item.source());
        return new Params(item, Quantity.optionalCount(params, "amount", 1, Give.MAX_AMOUNT),
                LocationSpec.read(params, "at", LocationSpec.Anchor.EVENT, false));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return Stages.now(executeResumable(params, context, ReadExecutor.INLINE));
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        return Quantities.resolve(context, reads, Quantities.orNull(params.amount())).thenCompose(q -> {
            int amount = 1;
            if (q[0] != null) {
                var whole = q[0].wholeValue(context);
                if (whole.isEmpty() || whole.getAsInt() < 1 || whole.getAsInt() > Give.MAX_AMOUNT) {
                    return Stages.failed("amount must be a whole number from 1 to " + Give.MAX_AMOUNT);
                }
                amount = whole.getAsInt();
            }
            int count = amount;
            return params.item().renderResumable(context, reads).thenCompose(text ->
                    params.at().resolveOffset(context, reads).thenCompose(at -> at.resolve(context, reads, verbs))
                            .thenCompose(where -> {
                                if (where.location() == null) {
                                    return Stages.failed(where.problem());
                                }
                                return drop(context, reads, text, count, where.location());
                            }));
        });
    }

    private CompletionStage<ActionOutcome> drop(
            LogicContext context, ReadExecutor reads, String item, int amount, Location location) {
        return reads.on(threading(), () -> catalog.current().orElse(null)).thenCompose(registries -> {
            ItemStack template;
            try {
                template = build(item, registries);
            } catch (IllegalArgumentException e) {
                return Stages.failed(e.getMessage());
            }
            List<Integer> sizes = Give.split(amount, template.getMaxStackSize());
            if (!WorldVerbs.within(context, WorldVerbs.DROPS, sizes.size(), verbs.limits().dropStacksPerRun())) {
                verbs.budgetSpent("drop_item", verbs.limits().dropStacksPerRun(), "stacks");
                return Stages.failed("this run has dropped its " + verbs.limits().dropStacksPerRun() + " stacks");
            }
            return verbs.regions().at(location, () -> {
                for (int size : sizes) {
                    ItemStack stack = template.clone();
                    stack.setAmount(size);
                    location.getWorld().dropItemNaturally(location, stack);
                }
                return ActionOutcome.OK;
            });
        });
    }

    private static ItemStack build(String item, RuntimeRegistries registries) {
        return ItemTemplates.of(ItemRef.resolve(item, registries), registries);
    }
}
