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
import java.util.List;
import org.bukkit.entity.Player;

/** {@code sound: "minecraft:block.note_block.pling"} or {@code sound: {sound, volume, pitch}}: plays a sound at the player. */
public final class SoundAction implements ActionType<SoundAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText sound, float volume, float pitch) {}

    @Override
    public String id() {
        return "sound";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Play sound", "Plays a sound at the player",
                List.of(
                        new ParameterSchema("sound", ParameterType.STRING, true, null,
                                "Sound key, e.g. minecraft:block.note_block.pling", null, null),
                        new ParameterSchema("volume", ParameterType.DECIMAL, false, "1", "Volume", null, null),
                        new ParameterSchema("pitch", ParameterType.DECIMAL, false, "1", "Pitch", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "sound");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                params.template("sound"),
                (float) params.decimal("volume", 1d, 0d, 1_000d),
                (float) params.decimal("pitch", 1d, 0d, 1_000d));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        player.playSound(player.getLocation(), params.sound().render(context), params.volume(), params.pitch());
        return ActionOutcome.OK;
    }
}
