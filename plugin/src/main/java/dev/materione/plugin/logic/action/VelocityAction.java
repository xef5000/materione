package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.api.logic.Quantity;
import dev.materione.plugin.logic.support.Stages;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * {@code velocity: {forward: 1.6, up: 0.3}} or {@code velocity: {x: 0, y: 1, z: 0, mode: add}}: pushes an entity
 * (logic.md §41). The subject is the run's player, or the {@code target:}.
 *
 * <p>The push is written one of two ways, never both. <b>Relative</b> ({@code forward}, {@code up}, {@code strafe}) is
 * measured from the subject's facing: {@code forward} follows the heading the subject looks along (horizontally, or along
 * the full look direction with {@code follow_pitch: true}), {@code strafe} is to its right, and {@code up} is always
 * world up. <b>Absolute</b> ({@code x}, {@code y}, {@code z}) is in world axes. The numbers are blocks per tick, as
 * Minecraft counts velocity, and each is bounded at 10. {@code mode: set} (the default) replaces the subject's velocity
 * and {@code mode: add} adds to it. Fails on a dead subject and on numbers that are not finite.
 */
public final class VelocityAction implements ActionType<VelocityAction.Params> {

    /** The largest absolute value of one component, in blocks per tick. */
    static final double MAX_COMPONENT = 10;

    /** Whether the push replaces the velocity or adds to it. */
    enum Mode { SET, ADD }

    /** The parsed step. */
    public record Params(Optional<Quantity> forward, Optional<Quantity> up, Optional<Quantity> strafe,
                         Optional<Quantity> x, Optional<Quantity> y, Optional<Quantity> z, Mode mode,
                         boolean followPitch) {
        boolean relative() {
            return forward.isPresent() || up.isPresent() || strafe.isPresent();
        }
    }

    @Override
    public String id() {
        return "velocity";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Velocity",
                "Pushes the player, or another entity given as a target: relative to its facing (forward, up, strafe) "
                        + "or along the world axes (x, y, z)",
                List.of(
                        number("forward", "Blocks per tick along the facing (horizontal unless follow_pitch)"),
                        number("up", "Blocks per tick upward"),
                        number("strafe", "Blocks per tick to the right of the facing"),
                        number("x", "Blocks per tick along the world X axis (not with forward, up or strafe)"),
                        number("y", "Blocks per tick along the world Y axis (not with forward, up or strafe)"),
                        number("z", "Blocks per tick along the world Z axis (not with forward, up or strafe)"),
                        new ParameterSchema("mode", ParameterType.ENUM, false, "set",
                                "set replaces the velocity, add adds to it", List.of("set", "add"), null),
                        new ParameterSchema("follow_pitch", ParameterType.BOOLEAN, false, "false",
                                "Whether forward follows where the subject looks, up or down, not only its heading",
                                null, null)),
                "2.0.0"), LogicCategory.WORLD, null).withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
    }

    private static ParameterSchema number(String id, String description) {
        return new ParameterSchema(id, ParameterType.DECIMAL, false, null, description, null, null)
                .acceptingExpression();
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        var forward = Quantity.optional(params, "forward", -MAX_COMPONENT, MAX_COMPONENT);
        var up = Quantity.optional(params, "up", -MAX_COMPONENT, MAX_COMPONENT);
        var strafe = Quantity.optional(params, "strafe", -MAX_COMPONENT, MAX_COMPONENT);
        var x = Quantity.optional(params, "x", -MAX_COMPONENT, MAX_COMPONENT);
        var y = Quantity.optional(params, "y", -MAX_COMPONENT, MAX_COMPONENT);
        var z = Quantity.optional(params, "z", -MAX_COMPONENT, MAX_COMPONENT);
        boolean relative = forward.isPresent() || up.isPresent() || strafe.isPresent();
        boolean absolute = x.isPresent() || y.isPresent() || z.isPresent();
        if (relative && absolute) {
            throw new IllegalArgumentException("write forward, up and strafe, or x, y and z, not both");
        }
        if (!relative && !absolute) {
            throw new IllegalArgumentException("a velocity needs forward, up or strafe, or x, y or z");
        }
        return new Params(forward, up, strafe, x, y, z,
                params.enumValue("mode", Mode.class, Mode.SET), params.bool("follow_pitch", false));
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
        return Quantities.resolve(context, reads, Quantities.orNull(params.forward()), Quantities.orNull(params.up()),
                Quantities.orNull(params.strafe()), Quantities.orNull(params.x()), Quantities.orNull(params.y()),
                Quantities.orNull(params.z())).thenCompose(q -> {
            double[] n = new double[6];
            for (int i = 0; i < 6; i++) {
                if (q[i] != null) {
                    OptionalDouble value = q[i].value(context);
                    if (value.isEmpty()) {
                        return Stages.failed("a velocity component must be a number from -10 to 10");
                    }
                    n[i] = value.getAsDouble();
                }
            }
            return reads.on(threading(), () -> push(subject, params, n));
        });
    }

    private static ActionOutcome push(Entity subject, Params params, double[] n) {
        if (subject.isDead() || !subject.isValid()) {
            return ActionOutcome.failed("the subject is dead");
        }
        Vector push = params.relative()
                ? relative(subject.getLocation(), n[0], n[1], n[2], params.followPitch())
                : new Vector(n[3], n[4], n[5]);
        Vector result = params.mode() == Mode.ADD ? subject.getVelocity().add(push) : push;
        if (!Double.isFinite(result.getX()) || !Double.isFinite(result.getY()) || !Double.isFinite(result.getZ())) {
            return ActionOutcome.failed("the velocity is not a finite number");
        }
        subject.setVelocity(result);
        return ActionOutcome.OK;
    }

    /** The push for {@code forward}, {@code up} and {@code strafe} measured from {@code where}'s facing. */
    static Vector relative(Location where, double forward, double up, double strafe, boolean followPitch) {
        double yaw = Math.toRadians(where.getYaw());
        Vector heading;
        if (followPitch) {
            heading = where.getDirection();
        } else {
            heading = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        return heading.multiply(forward).add(new Vector(0, up, 0)).add(right.multiply(strafe));
    }
}
