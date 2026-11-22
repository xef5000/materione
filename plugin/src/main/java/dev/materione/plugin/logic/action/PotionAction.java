package dev.materione.plugin.logic.action;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Quantity;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Quantities;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * {@code potion: {type: speed, duration: 200, amplifier: 1}}: applies a potion effect to the
 * player; duration is in ticks. The effect name is checked for shape at load; whether the server
 * knows it is answered when the step runs, because the effect registry belongs to the live server.
 * Duration and amplifier take a number or a template that renders to one (logic.md section 42, #470): a
 * literal is checked at load, a template is checked as the step runs, and a value out of range
 * refuses the step under {@code MTR-0120} with nothing applied.
 */
public final class PotionAction implements ActionType<PotionAction.Params> {

    /** The parsed step. */
    public record Params(NamespacedKey effect, Quantity duration, Quantity amplifier) {}

    @Override
    public String id() {
        return "potion";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Give potion effect", "Applies a potion effect to the player, or to another entity given as a target",
                List.of(
                        new ParameterSchema("type", ParameterType.STRING, true, null,
                                "Effect type, e.g. speed", null, null),
                        new ParameterSchema("duration", ParameterType.INTEGER, false, "200",
                                "Duration in ticks; or a template that renders to them", null, null).acceptingExpression(),
                        new ParameterSchema("amplifier", ParameterType.INTEGER, false, "0",
                                "Amplifier, 0 to 255; 0 is level I; or a template that renders to one", null, null)
                                .acceptingExpression()),
                "2.0.0"), LogicCategory.PLAYER).withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        String name = params.text("type");
        NamespacedKey effect;
        try {
            effect = NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type '" + name + "' is not a valid effect name", e);
        }
        return new Params(effect,
                Quantity.integerOr(params, "duration", 200, 1, Integer.MAX_VALUE),
                Quantity.integerOr(params, "amplifier", 0, 0, 255));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Entity subject = Subjects.entity(context).orElse(null);
        if (subject == null) {
            return ActionOutcome.failed("event has no player");
        }
        if (!(subject instanceof LivingEntity living)) {
            return ActionOutcome.failed("a potion effect needs a living entity");
        }
        OptionalInt duration = params.duration().wholeValue(context);
        if (duration.isEmpty()) {
            return ActionOutcome.refused(DiagnosticCodes.QUANTITY_REFUSED, params.duration().refusal("duration", context));
        }
        OptionalInt amplifier = params.amplifier().wholeValue(context);
        if (amplifier.isEmpty()) {
            return ActionOutcome.refused(DiagnosticCodes.QUANTITY_REFUSED, params.amplifier().refusal("amplifier", context));
        }
        PotionEffectType effect = Registry.EFFECT.get(params.effect());
        if (effect == null) {
            return ActionOutcome.failed("unknown potion effect '" + params.effect().getKey() + "'");
        }
        boolean added = living.addPotionEffect(new PotionEffect(effect, duration.getAsInt(), amplifier.getAsInt()));
        return added
                ? ActionOutcome.OK
                : ActionOutcome.failed("potion effect '" + params.effect().getKey() + "' was not applied");
    }

    /** The template duration and amplifier render on the thread their own reads need, then the effect is applied (as give_exp). */
    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        return Quantities.resolve(context, reads, params.duration(), params.amplifier())
                .thenCompose(resolved -> reads.on(threading(), () -> execute(
                        new Params(params.effect(), resolved[0], resolved[1]), context)));
    }
}
