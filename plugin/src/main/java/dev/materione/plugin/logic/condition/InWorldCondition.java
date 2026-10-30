package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code in_world: nether} or {@code in_world: [world, world_nether]} (logic.md §19): true when the player
 * is in one of the named worlds. World names are exact, as Bukkit keeps them.
 */
public final class InWorldCondition implements ConditionType<InWorldCondition.Params> {

    /** The world names the player may be in. */
    public record Params(Set<String> worlds) {}

    @Override
    public String id() {
        return "in_world";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("In world",
                "True when the player is in one of the named worlds",
                List.of(new ParameterSchema("worlds", ParameterType.RAW, true, null,
                        "One world name, or a list of them", null, null)),
                "2.0.0"), LogicCategory.WORLD, "worlds");
    }

    @Override
    public Params parse(ParamReader params) {
        if (params.raw("worlds").isEmpty()) {
            throw new IllegalArgumentException("worlds is required");
        }
        if (params.raw("worlds").orElseThrow() instanceof String) {
            return new Params(Set.of(params.text("worlds").trim()));
        }
        var names = params.strings("worlds").stream().map(String::trim).toList();
        if (names.isEmpty()) {
            throw new IllegalArgumentException("worlds needs at least one world name");
        }
        return new Params(Set.copyOf(names));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        World world = player.getWorld();
        return world != null && params.worlds().contains(world.getName());
    }
}
