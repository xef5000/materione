package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.api.logic.Quantity;
import dev.materione.plugin.logic.support.Stages;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.ToDoubleFunction;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/**
 * {@code heal: 4} or {@code heal: {amount: "${damage.final * 0.2}"}}: restores health to a living entity, up to its
 * maximum (logic.md §41). The subject is the run's player, or the {@code target:}. The heal goes through the server's
 * regain-health event, so a plugin that forbids healing is asked; an amount of 0 is a heal that did nothing, not a
 * failure, so an expression that evaluates to 0 never warns. Fails on a dead subject, on a subject that is not alive
 * (an item, an arrow), on a negative amount and on a heal a listener cancelled.
 */
public final class HealAction implements ActionType<HealAction.Params> {

    /** The most one step may heal. */
    static final double MAX_AMOUNT = 1_024;

    /** The parsed step. */
    public record Params(Quantity amount) {}

    private final ToDoubleFunction<LivingEntity> maxHealth;

    /** The heal of the running server: the ceiling is the entity's {@code max_health} attribute. */
    public HealAction() {
        this(living -> {
            AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
            return max == null ? living.getHealth() : max.getValue();
        });
    }

    /** @param maxHealth the ceiling of an entity's health (a test supplies one; the attribute needs a server) */
    public HealAction(ToDoubleFunction<LivingEntity> maxHealth) {
        this.maxHealth = maxHealth;
    }

    @Override
    public String id() {
        return "heal";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Heal",
                "Restores health to the player, or to another living entity given as a target, up to its maximum",
                List.of(new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                        "Health points to restore (2 is one heart); an expression is allowed; 0 does nothing",
                        null, null).acceptingExpression()),
                "2.0.0"), LogicCategory.PLAYER, "amount").withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(Quantity.required(params, "amount", 0, MAX_AMOUNT));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return Stages.now(executeResumable(params, context, ReadExecutor.INLINE));
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        Entity subject = Subjects.entity(context).orElse(null);
        if (subject == null) {
            return Stages.failed("event has no player");
        }
        if (!(subject instanceof LivingEntity living)) {
            return Stages.failed("a heal needs a living entity");
        }
        return Quantities.resolve(context, reads, params.amount()).thenCompose(resolved -> {
            var amount = resolved[0].value(context);
            if (amount.isEmpty()) {
                return Stages.failed("amount must be a number from 0 to " + (int) MAX_AMOUNT);
            }
            return reads.on(threading(), () -> heal(living, amount.getAsDouble(), maxHealth.applyAsDouble(living)));
        });
    }

    private static ActionOutcome heal(LivingEntity living, double amount, double ceiling) {
        if (living.isDead() || !living.isValid()) {
            return ActionOutcome.failed("the subject is dead");
        }
        double before = living.getHealth();
        if (amount <= 0 || before >= ceiling) {
            return ActionOutcome.OK;
        }
        living.heal(amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
        return living.getHealth() > before
                ? ActionOutcome.OK
                : ActionOutcome.failed("the heal was cancelled");
    }
}
