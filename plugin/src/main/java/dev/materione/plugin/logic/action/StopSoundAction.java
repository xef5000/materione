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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * {@code stop_sound: {sound, category}}: stops sounds for the player. With a sound key it stops
 * that sound, with a category it stops that category, with both it stops the sound in the
 * category, and with neither it stops everything playing for the player.
 */
public final class StopSoundAction implements ActionType<StopSoundAction.Params> {

    /** The parsed step. */
    public record Params(Optional<TemplateText> sound, SoundCategory category) {}

    @Override
    public String id() {
        return "stop_sound";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Stop sound",
                "Stops a sound, a sound category, or everything playing for the player",
                List.of(
                        new ParameterSchema("sound", ParameterType.STRING, false, null,
                                "Sound key to stop, e.g. minecraft:music.game", null, null),
                        new ParameterSchema("category", ParameterType.ENUM, false, null,
                                "Sound category to stop", categoryNames(), null)),
                "2.0.0"), LogicCategory.PLAYER, "sound");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    private static List<String> categoryNames() {
        return Arrays.stream(SoundCategory.values())
                .map(category -> category.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                params.optionalTemplate("sound"),
                params.enumValue("category", SoundCategory.class, null));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        SoundCategory category = params.category();
        if (params.sound().isPresent()) {
            String key = params.sound().get().render(context).trim();
            if (category == null) {
                player.stopSound(key);
            } else {
                player.stopSound(key, category);
            }
        } else if (category != null) {
            player.stopSound(category);
        } else {
            player.stopAllSounds();
        }
        return ActionOutcome.OK;
    }
}
