package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.RunMeter;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.Threading;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.world.DamageWatch;
import dev.materione.plugin.logic.world.VerbScope;
import dev.materione.plugin.protection.ProtectionService;
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

    private final DamageWatch watch;
    private final ProtectionService protection;

    /** A damage that cannot tell whether its blow was vetoed (every blow lands); the server's uses the other constructor. */
    public DamageAction() {
        this(DamageWatch.NONE);
    }

    /** @param watch what learns whether the blow was cancelled, at the last word of the event */
    public DamageAction(DamageWatch watch) {
        this(watch, ProtectionService.inert());
    }

    /**
     * @param watch what learns whether the blow was cancelled, at the last word of the event
     * @param protection the protection port, asked whether the attacker may hurt the victim when the mode asks it (logic.md §43)
     */
    public DamageAction(DamageWatch watch, ProtectionService protection) {
        this.watch = watch;
        this.protection = protection;
    }

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
            Object rule = context.facet(RunMeter.FACET).map(RunMeter::origin).orElse(null);
            return reads.on(threading(), () -> hurt(victim, subject, attacker, amount.getAsDouble(), params.cause(), rule));
        });
    }

    @SuppressWarnings("try") // the scope is entered for its lifetime; nothing reads it
    private ActionOutcome hurt(Damageable victim, Entity subject, Player attacker, double amount,
            Optional<NamespacedKey> cause, Object rule) {
        if (subject.isDead() || !subject.isValid()) {
            return ActionOutcome.failed("the subject is dead");
        }
        if (amount <= 0) {
            return ActionOutcome.OK;
        }
        Entity responsible = attacker != null && attacker != subject ? attacker : null;
        if (protection.asksPort() && attacker != subject) {
            // the port answers first (a PvP flag, a claim's no-damage rule); the damage event below is the effect itself, so the
            // event path is not a separate check here and port and both behave alike (protection.md section 8)
            ProtectionActor actor = attacker == null ? ProtectionActor.none() : ProtectionActor.of(attacker);
            ProtectionAnswer answer = protection.canDamage(actor, subject);
            if (answer.denied()) {
                return ActionOutcome.failed("the damage was " + answer.describeDenial());
            }
        }
        try (var scope = VerbScope.enter(rule)) {
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
                return landed(subject, () -> victim.damage(amount, source.build()));
            } else if (responsible != null) {
                return landed(subject, () -> victim.damage(amount, responsible));
            } else {
                return landed(subject, () -> victim.damage(amount));
            }
        }
    }

    /** The blow's outcome: a damage event a listener cancelled is a failed step ({@code require} can answer it), said quietly. */
    private ActionOutcome landed(Entity subject, Runnable blow) {
        return watch.observe(subject, blow) == DamageWatch.Result.CANCELLED
                ? ActionOutcome.failed("the damage was cancelled by a listener")
                : ActionOutcome.OK;
    }
}
