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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code sound: "minecraft:block.note_block.pling"} or {@code sound: {sound, volume, pitch, scope}}:
 * plays a sound. {@code scope: player} (the default) plays it to the player; {@code world} plays it
 * at the player's location to everyone in their world; {@code server} plays it to everyone online.
 *
 * <p>Declared {@link Threading#GLOBAL}: the world and server scopes touch other players, and the
 * type's threading is static (logic.md §14, decision 6), so it takes the stricter choice.
 */
public final class SoundAction implements ActionType<SoundAction.Params> {

    /** Who hears the sound. */
    public enum Scope { PLAYER, WORLD, SERVER }

    /** The parsed step. */
    public record Params(TemplateText sound, float volume, float pitch, Scope scope) {}

    @Override
    public String id() {
        return "sound";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Play sound", "Plays a sound to the player, their world or the server",
                List.of(
                        new ParameterSchema("sound", ParameterType.STRING, true, null,
                                "Sound key, e.g. minecraft:block.note_block.pling", null, null),
                        new ParameterSchema("volume", ParameterType.DECIMAL, false, "1", "Volume", null, null),
                        new ParameterSchema("pitch", ParameterType.DECIMAL, false, "1", "Pitch", null, null),
                        new ParameterSchema("scope", ParameterType.ENUM, false, "player",
                                "Who hears it", List.of("player", "world", "server"), null)),
                "2.0.0"), LogicCategory.PLAYER, "sound");
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                params.template("sound"),
                (float) params.decimal("volume", 1d, 0d, 1_000d),
                (float) params.decimal("pitch", 1d, 0d, 1_000d),
                params.enumValue("scope", Scope.class, Scope.PLAYER));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (params.scope() == Scope.SERVER) {
            String key = params.sound().render(context);
            for (Player listener : Bukkit.getOnlinePlayers()) {
                listener.playSound(listener.getLocation(), key, params.volume(), params.pitch());
            }
            return ActionOutcome.OK;
        }
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        String key = params.sound().render(context);
        if (params.scope() == Scope.WORLD) {
            for (Player listener : player.getWorld().getPlayers()) {
                listener.playSound(player.getLocation(), key, params.volume(), params.pitch());
            }
            return ActionOutcome.OK;
        }
        player.playSound(player.getLocation(), key, params.volume(), params.pitch());
        return ActionOutcome.OK;
    }
}
