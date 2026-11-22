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
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.world.RunStacks;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * {@code launch: {projectile: small_fireball, speed: 1.5}}: shoots a projectile from an entity's eyes, along where it
 * looks (logic.md §41). The shooter is the run's player, or the {@code target:}. {@code projectile} is any entity type
 * the server can launch (an arrow, a snowball, a fireball, a trident, a wither skull); {@code speed} is blocks per tick;
 * {@code spread} is a cone in degrees, and {@code count} fires several, each with its own spread.
 *
 * <p>The projectile goes through the server's own launch path (so a protection plugin may veto it) and carries the id of
 * the item the run is about, so when it lands {@code entity.hit} runs that item's rules even if the shooter has switched
 * hands. Fails when the type is not a projectile, the shooter cannot shoot, or every launch was vetoed.
 *
 * <p><b>Budgets.</b> One step launches at most {@link WorldVerbs#MAX_LAUNCH_PER_STEP}; one run at most
 * {@link WorldVerbs#MAX_LAUNCHES_PER_RUN} ({@code MTR-0118}, and the step fails).
 */
public final class LaunchAction implements ActionType<LaunchAction.Params> {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_./:-]+");

    /** The parsed step. */
    public record Params(String projectile, Quantity speed, Quantity spread, Quantity count) {}

    private final WorldVerbs verbs;

    /** @param verbs the platform: the launch seam and the budget reporter */
    public LaunchAction(WorldVerbs verbs) {
        this.verbs = verbs;
    }

    @Override
    public String id() {
        return "launch";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Launch projectile",
                "Shoots a projectile from the player's eyes, or from another entity given as a target; "
                        + "the projectile remembers the item the rule belongs to",
                List.of(
                        new ParameterSchema("projectile", ParameterType.STRING, true, null,
                                "An entity type that can be launched: arrow, snowball, small_fireball, trident, ...",
                                null, null),
                        new ParameterSchema("speed", ParameterType.DECIMAL, false, "1.5",
                                "Blocks per tick, 0.05 to 10", null, null).acceptingExpression(),
                        new ParameterSchema("spread", ParameterType.DECIMAL, false, "0",
                                "A cone around the aim, in degrees, 0 to 45", null, null).acceptingExpression(),
                        new ParameterSchema("count", ParameterType.INTEGER, false, "1",
                                "How many, 1 to " + verbs.limits().launchesPerStep(), null, null).acceptingExpression()),
                "2.0.0"), LogicCategory.WORLD, "projectile").withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        String name = params.text("projectile").toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("projectile '" + name + "' is not a valid entity type name");
        }
        return new Params(name,
                Quantity.optional(params, "speed", 0.05, 10).orElse(new Quantity(1.5, null, 0.05, 10, false)),
                Quantity.optional(params, "spread", 0, 45).orElse(new Quantity(0, null, 0, 45, false)),
                Quantity.optionalCount(params, "count", 1, verbs.limits().launchesPerStep())
                        .orElse(new Quantity(1, null, 1, verbs.limits().launchesPerStep(), true)));
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
        if (!(subject instanceof ProjectileSource shooter) || !(subject instanceof LivingEntity living)) {
            return Stages.failed("only a living entity can launch a projectile");
        }
        EntityType type = verbs.projectiles().projectile(params.projectile()).orElse(null);
        if (type == null) {
            return Stages.failed("'" + params.projectile() + "' is not a projectile this server can launch");
        }
        Optional<String> item = RunStacks.identity(context);
        return Quantities.resolve(context, reads, params.speed(), params.spread(), params.count()).thenCompose(q -> {
            var speed = q[0].value(context);
            var spread = q[1].value(context);
            var count = q[2].wholeValue(context);
            if (speed.isEmpty() || spread.isEmpty() || count.isEmpty()) {
                return Stages.failed("speed, spread and count must be numbers in range");
            }
            if (!WorldVerbs.within(context, WorldVerbs.LAUNCHES, count.getAsInt(), verbs.limits().launchesPerRun())) {
                verbs.budgetSpent("launch", verbs.limits().launchesPerRun(), "projectiles");
                return Stages.failed("this run has launched its " + verbs.limits().launchesPerRun() + " projectiles");
            }
            return reads.on(threading(), () -> fire(living, shooter, type, speed.getAsDouble(), spread.getAsDouble(),
                    count.getAsInt(), item));
        });
    }

    private ActionOutcome fire(LivingEntity living, ProjectileSource shooter, EntityType type, double speed,
            double spread, int count, Optional<String> item) {
        if (living.isDead() || !living.isValid()) {
            return ActionOutcome.failed("the shooter is dead");
        }
        Vector aim = living.getEyeLocation().getDirection();
        int launched = 0;
        for (int i = 0; i < count; i++) {
            Vector direction = spread <= 0 ? aim.clone() : cone(aim, spread);
            if (verbs.projectiles().launch(shooter, type, direction.multiply(speed), item).isPresent()) {
                launched++;
            }
        }
        return launched > 0 ? ActionOutcome.OK : ActionOutcome.failed("the launch was refused");
    }

    /** {@code aim} turned by a random angle of at most {@code degrees}, uniformly over the cone. */
    static Vector cone(Vector aim, double degrees) {
        var random = ThreadLocalRandom.current();
        double tilt = Math.toRadians(degrees) * Math.sqrt(random.nextDouble());
        double around = random.nextDouble() * 2 * Math.PI;
        Vector axis = aim.clone().normalize();
        Vector helper = Math.abs(axis.getY()) < 0.99 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector u = axis.clone().crossProduct(helper).normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        return axis.multiply(Math.cos(tilt))
                .add(u.multiply(Math.sin(tilt) * Math.cos(around)))
                .add(v.multiply(Math.sin(tilt) * Math.sin(around)));
    }
}
