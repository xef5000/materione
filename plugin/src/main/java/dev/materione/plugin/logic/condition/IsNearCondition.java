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
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code is_near: {world: shop, x: 0, y: 64, z: 0, distance: 8}} (logic.md §22). True when the player is
 * within {@code distance} blocks of the point, measured straight (not along the ground). {@code world} is
 * optional: without it the point is in whatever world the player is in, and a point in another world is
 * never near.
 *
 * <p>Every number may be a template ({@code x: "${vars.spawn_x}"}). A template that does not render to a
 * number in range makes the condition false, as does a player with no world.
 */
public final class IsNearCondition implements ConditionType<IsNearCondition.Params> {

    /** The largest coordinate or distance: the world border's reach. */
    static final double MAX_COORDINATE = 30_000_000.0;

    /** The parsed condition; {@code world} is the authored name, or empty for any. */
    public record Params(Optional<String> world, Quantity x, Quantity y, Quantity z, Quantity distance) {}

    @Override
    public String id() {
        return "is_near";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Is near",
                "True when the player is within the distance of a point, in the named world",
                List.of(
                        new ParameterSchema("world", ParameterType.STRING, false, null,
                                "World name; the player's own world when omitted", null, null),
                        new ParameterSchema("x", ParameterType.DECIMAL, true, null,
                                "The point's x coordinate (a number or a template)", null, null),
                        new ParameterSchema("y", ParameterType.DECIMAL, true, null,
                                "The point's y coordinate (a number or a template)", null, null),
                        new ParameterSchema("z", ParameterType.DECIMAL, true, null,
                                "The point's z coordinate (a number or a template)", null, null),
                        new ParameterSchema("distance", ParameterType.DECIMAL, true, null,
                                "Radius in blocks, from 0 (a number or a template)", null, null)),
                "2.0.0"), LogicCategory.WORLD, null);
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                params.optionalText("world"),
                Quantity.required(params, "x", -MAX_COORDINATE, MAX_COORDINATE),
                Quantity.required(params, "y", -MAX_COORDINATE, MAX_COORDINATE),
                Quantity.required(params, "z", -MAX_COORDINATE, MAX_COORDINATE),
                Quantity.required(params, "distance", 0, 2 * MAX_COORDINATE));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        Location at = player.getLocation();
        World world = at == null ? null : at.getWorld();
        if (world == null || (params.world().isPresent() && !params.world().get().equals(world.getName()))) {
            return false;
        }
        OptionalDouble x = params.x().value(context);
        OptionalDouble y = params.y().value(context);
        OptionalDouble z = params.z().value(context);
        OptionalDouble radius = params.distance().value(context);
        if (x.isEmpty() || y.isEmpty() || z.isEmpty() || radius.isEmpty()) {
            return false;
        }
        double dx = at.getX() - x.getAsDouble();
        double dy = at.getY() - y.getAsDouble();
        double dz = at.getZ() - z.getAsDouble();
        double r = radius.getAsDouble();
        return dx * dx + dy * dy + dz * dz <= r * r;
    }
}
