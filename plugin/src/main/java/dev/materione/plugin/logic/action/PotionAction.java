package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * {@code potion: {type: speed, duration: 200, amplifier: 1}}: applies a potion effect to the
 * player; duration is in ticks. The effect name is checked for shape at load; whether the server
 * knows it is answered when the step runs, because the effect registry belongs to the live server.
 */
public final class PotionAction implements ActionType<PotionAction.Params> {

    /** The parsed step. */
    public record Params(NamespacedKey effect, int duration, int amplifier) {}

    @Override
    public String id() {
        return "potion";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Give potion effect", "Applies a potion effect to the player",
                List.of(
                        new ParameterSchema("type", ParameterType.STRING, true, null,
                                "Effect type, e.g. speed", null, null),
                        new ParameterSchema("duration", ParameterType.INTEGER, false, "200",
                                "Duration in ticks", null, null),
                        new ParameterSchema("amplifier", ParameterType.INTEGER, false, "0",
                                "Amplifier; 0 is level I", null, null)),
                "2.0.0"), LogicCategory.PLAYER);
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
                params.integer("duration", 200, 1, Integer.MAX_VALUE),
                params.integer("amplifier", 0, 0, 255));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        PotionEffectType effect = Registry.EFFECT.get(params.effect());
        if (effect == null) {
            return ActionOutcome.failed("unknown potion effect '" + params.effect().getKey() + "'");
        }
        boolean added = player.addPotionEffect(new PotionEffect(effect, params.duration(), params.amplifier()));
        return added
                ? ActionOutcome.OK
                : ActionOutcome.failed("potion effect '" + params.effect().getKey() + "' was not applied");
    }
}
