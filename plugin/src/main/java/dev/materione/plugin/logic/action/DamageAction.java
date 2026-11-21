package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
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
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.world.VerbScope;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code damage: 6} or {@code damage: {amount: 6, cause: magic}}: hurts an entity (logic.md §41). The subject is the
 * run's player, or the {@code target:}. When the run has a player and the subject is someone else, the damage is
 * attributed to that player, so the server raises an ordinary entity-damage event with a damager and protection, combat
 * and kill-credit plugins see a blow like any other; with no player (or a self-inflicted one) it is an unattributed
 * damage of the given {@code cause} ({@code generic} by default). {@code cause} is a vanilla damage type
 * ({@code magic}, {@code fire}, {@code player_attack}, ...).
 *
 * <p>The damage the verb raises is the rule's own effect: the bridge does not fire {@code entity.hit} or
 * {@code player.hurt} for it (see {@code VerbScope}), so a rule that damages in answer to a hit does not answer itself.
 * Bukkit's {@code damage} reports no veto, so a hit a protection plugin cancelled is still an OK step; the step fails
 * only for a dead subject or one that cannot be damaged.
 */
public final class DamageAction implements ActionType<DamageAction.Params> {

    /** The most one step may deal. */
    static final double MAX_AMOUNT = 10_000;

    /** The parsed step. */
    public record Params(Quantity amount, Optional<NamespacedKey> cause) {}

    @Override
    public String id() {
        return "damage";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Damage",
                "Hurts the player, or another entity given as a target; attributed to the run's player when there is one",
                List.of(
                        new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                                "Health points of damage (2 is one heart); an expression is allowed", null, null)
                                .acceptingExpression(),
                        new ParameterSchema("cause", ParameterType.STRING, false, null,
                                "A vanilla damage type such as magic, fire or player_attack; default is a player attack "
                                        + "when attributed, generic otherwise", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "amount").withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        Optional<NamespacedKey> cause = params.optionalText("cause").map(name -> {
            try {
                return NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("cause '" + name + "' is not a valid damage type name", e);
            }
        });
        return new Params(Quantity.required(params, "amount", 0, MAX_AMOUNT), cause);
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
        if (!(subject instanceof Damageable victim)) {
            return Stages.failed("damage needs an entity that can be hurt");
        }
        Player attacker = context.facet(Facets.PLAYER).orElse(null);
        return Quantities.resolve(context, reads, params.amount()).thenCompose(resolved -> {
            var amount = resolved[0].value(context);
            if (amount.isEmpty()) {
                return Stages.failed("amount must be a number from 0 to " + (int) MAX_AMOUNT);
            }
            return reads.on(threading(), () -> hurt(victim, subject, attacker, amount.getAsDouble(), params.cause()));
        });
    }

    @SuppressWarnings("try") // the scope is entered for its lifetime; nothing reads it
    private static ActionOutcome hurt(Damageable victim, Entity subject, Player attacker, double amount,
            Optional<NamespacedKey> cause) {
        if (subject.isDead() || !subject.isValid()) {
            return ActionOutcome.failed("the subject is dead");
        }
        if (amount <= 0) {
            return ActionOutcome.OK;
        }
        Entity responsible = attacker != null && attacker != subject ? attacker : null;
        try (var scope = VerbScope.damaging()) {
            if (cause.isPresent()) {
                DamageType type = io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.DAMAGE_TYPE).get(cause.get());
                if (type == null) {
                    return ActionOutcome.failed("unknown damage type '" + cause.get().getKey() + "'");
                }
                DamageSource.Builder source = DamageSource.builder(type);
                if (responsible != null) {
                    source.withCausingEntity(responsible).withDirectEntity(responsible);
                }
                victim.damage(amount, source.build());
            } else if (responsible != null) {
                victim.damage(amount, responsible);
            } else {
                victim.damage(amount);
            }
        }
        return ActionOutcome.OK;
    }
}
