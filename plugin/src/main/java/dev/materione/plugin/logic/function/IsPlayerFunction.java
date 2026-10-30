package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * {@code is_player(name)}: true when {@code name} (a player name, or a UUID) names the player this
 * run belongs to. It reads the {@link Facets#PLAYER} facet of the run and looks nobody else up, so it
 * is pure and needs no server call: a run without a player (an explosion, a timer) answers false.
 *
 * <p>Plugin-side rather than in {@code behavior}, because the facet is a Bukkit type.
 */
public final class IsPlayerFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "is_player";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Is this player",
                "True when the name or UUID is the player this run belongs to. It does not look up other players, "
                        + "and a run without a player is false.",
                List.of(param("name", ParameterType.STRING, true, "A player name or UUID")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        if (!(arguments.get(0) instanceof String name)) {
            return false;
        }
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        String wanted = name.strip();
        if (wanted.equalsIgnoreCase(player.getName())) {
            return true;
        }
        UUID id = player.getUniqueId();
        return id != null && wanted.equalsIgnoreCase(id.toString());
    }
}
