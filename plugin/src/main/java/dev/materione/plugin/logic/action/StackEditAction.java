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
import dev.materione.platform.text.ItemText;
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.variable.BukkitStackRef;
import dev.materione.plugin.logic.world.RunStacks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * {@code stack.edit: {name: "<red>Bloodfang", lore: ["Level ${vars.item.rpg.level}"]}}: patches the item stack the run is
 * about (logic.md §41, §38 decision 4: the stack the trigger declared, the one {@code vars.item.*} reads). It sets the
 * fields it is given and touches nothing else: the item's custom name, its lore lines, its item model and its damage.
 * It never replaces the stack, so its identity, its item variables, its enchantments and its count survive, and an edit
 * lands on the live stack the player holds. Text is the item text of everywhere else in Materione (MiniMessage, italics off
 * by default), and may carry templates; {@code lore: []} clears the lore.
 *
 * <p>The run's stack is the one the trigger passes, so the verb has a stack exactly when an item expression would: a
 * rule on a trigger with no item, or an empty hand, fails with "the run has no item stack". A stack that was moved away and
 * replaced by another item while the run was parked is refused, as an item variable's write is. The damage field is the
 * vanilla {@code damage} component; Materione's own durability mechanic keeps its counter beside it and is not touched.
 */
public final class StackEditAction implements ActionType<StackEditAction.Params> {

    /** The parsed step. */
    public record Params(Optional<TemplateText> name, Optional<List<TemplateText>> lore, Optional<NamespacedKey> model,
                         Optional<Quantity> damage) {}

    @Override
    public String id() {
        return "stack.edit";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Edit item",
                "Patches the item stack the rule is about: name, lore, item model or damage; the stack itself is kept",
                List.of(
                        new ParameterSchema("name", ParameterType.STRING, false, null,
                                "The item's custom name (MiniMessage; templates allowed)", null, null)
                                .acceptingExpression(),
                        new ParameterSchema("lore", ParameterType.LIST, false, null,
                                "The lore lines (MiniMessage; ${...} templates allowed); an empty list clears them", null, null)
                                .acceptingExpression(),
                        new ParameterSchema("model", ParameterType.STRING, false, null,
                                "The item model to show, as a namespaced id", null, null),
                        new ParameterSchema("damage", ParameterType.INTEGER, false, null,
                                "Durability used (the vanilla damage); an expression is allowed", null, null)
                                .acceptingExpression()),
                "2.0.0"), LogicCategory.PLAYER);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        // the run's stack is declared by the trigger: a trigger that is not about an item gets MTR-0060 at load (logic.md section 38)
        params.requireRoot("item", "stack.edit needs a trigger that is about an item stack");
        Optional<TemplateText> name = params.optionalTemplate("name");
        Optional<List<TemplateText>> lore = params.raw("lore").isPresent()
                ? Optional.of(params.templates("lore")) : Optional.empty();
        Optional<NamespacedKey> model = params.optionalText("model").map(text -> {
            NamespacedKey key = NamespacedKey.fromString(text.toLowerCase(java.util.Locale.ROOT));
            if (key == null) {
                throw new IllegalArgumentException("model '" + text + "' is not a valid namespaced id");
            }
            return key;
        });
        Optional<Quantity> damage = Quantity.optionalCount(params, "damage", 0, 1_000_000);
        if (name.isEmpty() && lore.isEmpty() && model.isEmpty() && damage.isEmpty()) {
            throw new IllegalArgumentException("stack.edit needs at least one of name, lore, model or damage");
        }
        return new Params(name, lore, model, damage);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return Stages.now(executeResumable(params, context, ReadExecutor.INLINE));
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        BukkitStackRef stack = RunStacks.of(context).orElse(null);
        if (stack == null) {
            return Stages.failed("the run has no item stack to edit");
        }
        return render(params.name(), context, reads).thenCompose(name ->
                renderAll(params.lore(), context, reads).thenCompose(lore ->
                        Quantities.resolve(context, reads, Quantities.orNull(params.damage())).thenCompose(q -> {
                            Integer damage = null;
                            if (q[0] != null) {
                                var whole = q[0].wholeValue(context);
                                if (whole.isEmpty()) {
                                    return Stages.failed("damage must be a whole number from 0 to 1000000");
                                }
                                damage = whole.getAsInt();
                            }
                            Integer used = damage;
                            return reads.on(threading(), () -> edit(stack, name, lore, params.model(), used));
                        })));
    }

    private static ActionOutcome edit(BukkitStackRef stack, Optional<String> name, Optional<List<String>> lore,
            Optional<NamespacedKey> model, Integer damage) {
        String[] problem = {null};
        boolean edited = stack.editMeta(meta -> {
            name.ifPresent(text -> meta.displayName(ItemText.text(text)));
            lore.ifPresent(lines -> meta.lore(lines.isEmpty() ? null : toComponents(lines)));
            model.ifPresent(meta::setItemModel);
            if (damage != null) {
                problem[0] = setDamage(meta, damage);
            }
        });
        if (problem[0] != null) {
            return ActionOutcome.failed(problem[0]);
        }
        return edited ? ActionOutcome.OK : ActionOutcome.failed("the item stack is gone or is now another item");
    }

    private static String setDamage(ItemMeta meta, int damage) {
        if (!(meta instanceof Damageable damageable)) {
            return "this item has no durability to set";
        }
        int clamped = damageable.hasMaxDamage() ? Math.min(damage, Math.max(0, damageable.getMaxDamage() - 1)) : damage;
        damageable.setDamage(clamped);
        return null;
    }

    private static List<Component> toComponents(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(ItemText.text(line));
        }
        return out;
    }

    private static CompletionStage<Optional<String>> render(
            Optional<TemplateText> text, LogicContext context, ReadExecutor reads) {
        return text.isEmpty() ? CompletableFuture.completedFuture(Optional.empty())
                : text.get().renderResumable(context, reads).thenApply(Optional::of);
    }

    private static CompletionStage<Optional<List<String>>> renderAll(
            Optional<List<TemplateText>> lines, LogicContext context, ReadExecutor reads) {
        if (lines.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        List<String> out = new ArrayList<>();
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (TemplateText line : lines.get()) {
            chain = chain.thenCompose(ignored -> line.renderResumable(context, reads)).thenAccept(out::add);
        }
        return chain.thenApply(ignored -> Optional.of(out));
    }
}
