package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.ResolvedText;
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.OptionalDouble;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code teleport: {x, y, z, world, yaw, pitch}}: moves the player. Coordinates may be templates
 * ({@code x: "${vars.home_x}"}). Without a world the player stays in theirs, and without yaw and
 * pitch they keep their facing.
 */
public final class TeleportAction implements ActionType<TeleportAction.Params> {

    /** Coordinates beyond this are rejected: they are outside any world border a server can hold. */
    static final double MAX_COORDINATE = 30_000_000d;

    /** The parsed step. */
    public record Params(Quantity x, Quantity y, Quantity z, Optional<TemplateText> world,
                         Optional<Quantity> yaw, Optional<Quantity> pitch) {}

    @Override
    public String id() {
        return "teleport";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Teleport",
                "Moves the player to coordinates, optionally in another world",
                List.of(
                        new ParameterSchema("x", ParameterType.DECIMAL, true, null, "X coordinate", null, null),
                        new ParameterSchema("y", ParameterType.DECIMAL, true, null, "Y coordinate", null, null),
                        new ParameterSchema("z", ParameterType.DECIMAL, true, null, "Z coordinate", null, null),
                        new ParameterSchema("world", ParameterType.STRING, false, null,
                                "World name; default the player's world", null, null),
                        new ParameterSchema("yaw", ParameterType.DECIMAL, false, null,
                                "Facing, degrees; default keeps the current facing", null, null),
                        new ParameterSchema("pitch", ParameterType.DECIMAL, false, null,
                                "Look up or down, degrees; default keeps the current pitch", null, null)),
                "2.0.0"), LogicCategory.WORLD, null);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                Quantity.required(params, "x", -MAX_COORDINATE, MAX_COORDINATE),
                Quantity.required(params, "y", -MAX_COORDINATE, MAX_COORDINATE),
                Quantity.required(params, "z", -MAX_COORDINATE, MAX_COORDINATE),
                params.optionalTemplate("world"),
                Quantity.optional(params, "yaw", -360d, 360d),
                Quantity.optional(params, "pitch", -90d, 90d));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        Location here = player.getLocation();
        World world = here.getWorld();
        if (params.world().isPresent()) {
            String name = params.world().get().render(context).trim();
            world = Bukkit.getWorld(name);
            if (world == null) {
                return ActionOutcome.failed("no world named '" + name + "'");
            }
        }
        var x = params.x().value(context);
        var y = params.y().value(context);
        var z = params.z().value(context);
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return ActionOutcome.failed("teleport coordinates must be numbers");
        }
        var yaw = params.yaw().isPresent() ? params.yaw().get().value(context) : OptionalDouble.of(here.getYaw());
        var pitch = params.pitch().isPresent() ? params.pitch().get().value(context) : OptionalDouble.of(here.getPitch());
        if (yaw.isEmpty() || pitch.isEmpty()) {
            return ActionOutcome.failed("teleport yaw and pitch must be numbers in range");
        }
        return teleport(player, world, x, y, z, yaw, pitch);
    }

    private static ActionOutcome teleport(Player player, World world, OptionalDouble x, OptionalDouble y,
            OptionalDouble z, OptionalDouble yaw, OptionalDouble pitch) {
        var destination = new Location(world, x.getAsDouble(), y.getAsDouble(), z.getAsDouble(),
                (float) yaw.getAsDouble(), (float) pitch.getAsDouble());
        return player.teleport(destination)
                ? ActionOutcome.OK
                : ActionOutcome.failed("the server refused the teleport");
    }

    private static CompletionStage<Optional<Quantity>> resolve(
            Optional<Quantity> quantity, LogicContext context, ReadExecutor reads) {
        return quantity.isEmpty() ? CompletableFuture.completedFuture(Optional.empty())
                : quantity.get().resolveResumable(context, reads).thenApply(Optional::of);
    }

    @Override public CompletionStage<ActionOutcome> executeResumable(
            Params params, LogicContext context, ReadExecutor reads) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture(ActionOutcome.failed("event has no player"));
        }
        return reads.on(threading(), player::getLocation).thenCompose(here ->
                ResolvedText.resolve(params.world(), context, reads).thenCompose(name ->
                        reads.on(threading(), () -> name.isPresent()
                                ? Bukkit.getWorld(name.get().render(context).trim()) : here.getWorld())
                        .thenCompose(world -> {
                            if (world == null && name.isPresent()) {
                                return CompletableFuture.completedFuture(ActionOutcome.failed(
                                        "no world named '" + name.get().render(context).trim() + "'"));
                            }
                            return params.x().resolveResumable(context, reads).thenCompose(x ->
                                    params.y().resolveResumable(context, reads).thenCompose(y ->
                                            params.z().resolveResumable(context, reads).thenCompose(z -> {
                                                var xv = x.value(context);
                                                var yv = y.value(context);
                                                var zv = z.value(context);
                                                if (xv.isEmpty() || yv.isEmpty() || zv.isEmpty()) {
                                                    return CompletableFuture.completedFuture(ActionOutcome.failed(
                                                            "teleport coordinates must be numbers"));
                                                }
                                                return resolve(params.yaw(), context, reads).thenCompose(yaw ->
                                                        resolve(params.pitch(), context, reads).thenCompose(pitch -> {
                                                            var yawv = yaw.isPresent() ? yaw.get().value(context)
                                                                    : OptionalDouble.of(here.getYaw());
                                                            var pitchv = pitch.isPresent() ? pitch.get().value(context)
                                                                    : OptionalDouble.of(here.getPitch());
                                                            if (yawv.isEmpty() || pitchv.isEmpty()) {
                                                                return CompletableFuture.completedFuture(
                                                                        ActionOutcome.failed(
                                                                                "teleport yaw and pitch must be numbers in range"));
                                                            }
                                                            return reads.on(threading(),
                                                                    () -> teleport(player, world, xv, yv, zv, yawv, pitchv));
                                                        }));
                                            })));
                        })));
    }
}
