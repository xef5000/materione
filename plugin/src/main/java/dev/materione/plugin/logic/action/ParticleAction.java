package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Quantities;
import dev.materione.plugin.logic.support.Quantity;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.support.Triple;
import dev.materione.plugin.logic.world.LocationSpec;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;

/**
 * {@code particle: {particle: cloud, count: 20}}: spawns particles (logic.md §41). They appear at {@code at} (default:
 * the entity the action acts on, so {@code target:} moves the trail), spread by {@code offset} blocks around it, with
 * {@code speed} (the particle's own motion, in the units the client gives each type). {@code color} ({@code "#rrggbb"})
 * and {@code size} serve the particles that carry a colour ({@code dust}, {@code entity_effect}); a particle that needs
 * other data (a block, an item, a transition) is refused with the reason. Clients near the location see them, as for
 * any particle the server spawns.
 *
 * <p><b>Budgets.</b> One step spawns at most {@link WorldVerbs#MAX_PARTICLES_PER_STEP} (checked at load for a literal
 * {@code count}); one run at most {@link WorldVerbs#MAX_PARTICLES_PER_RUN} ({@code MTR-0118}, and the step fails).
 */
public final class ParticleAction implements ActionType<ParticleAction.Params> {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_./:-]+");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /** The parsed step. */
    public record Params(String particle, Quantity count, Optional<Triple> offset, Quantity speed,
                         LocationSpec at, Optional<Color> color, Quantity size) {}

    private final WorldVerbs verbs;

    /** @param verbs the platform: the region seam, the world lookup and the budget reporter */
    public ParticleAction(WorldVerbs verbs) {
        this.verbs = verbs;
    }

    @Override
    public String id() {
        return "particle";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Particle",
                "Spawns particles at a location: the player, or another entity given as a target, by default",
                List.of(
                        new ParameterSchema("particle", ParameterType.STRING, true, null,
                                "A vanilla particle such as cloud, flame, heart or dust", null, null),
                        new ParameterSchema("count", ParameterType.INTEGER, false, "1",
                                "How many, 1 to " + verbs.limits().particlesPerStep() + " per step", null, null)
                                .acceptingExpression(),
                        new ParameterSchema("offset", ParameterType.RAW, false, null,
                                "Spread in blocks: one number, [x, y, z], or {x, y, z}", null, null),
                        new ParameterSchema("speed", ParameterType.DECIMAL, false, "0",
                                "The particle's own motion; its unit depends on the particle", null, null)
                                .acceptingExpression(),
                        new ParameterSchema("at", ParameterType.RAW, false, "target",
                                "Where: event, player, target, or {of, x, y, z} with a world-axis offset in blocks",
                                null, null),
                        new ParameterSchema("color", ParameterType.STRING, false, null,
                                "#rrggbb, for particles that carry a colour (dust, entity_effect)", null, null),
                        new ParameterSchema("size", ParameterType.DECIMAL, false, "1",
                                "The size of a dust particle, 0.1 to 4", null, null).acceptingExpression()),
                "2.0.0"), LogicCategory.WORLD, "particle").withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        String name = params.text("particle").toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("particle '" + name + "' is not a valid particle name");
        }
        Quantity count = params.raw("count").isPresent()
                ? Quantity.requiredCount(params, "count", 1, verbs.limits().particlesPerStep())
                : new Quantity(1, null, 1, verbs.limits().particlesPerStep(), true);
        Quantity speed = Quantity.optional(params, "speed", 0, 10).orElse(new Quantity(0, null, 0, 10, false));
        Quantity size = Quantity.optional(params, "size", 0.1, 4).orElse(new Quantity(1, null, 0.1, 4, false));
        Optional<Color> color = params.optionalText("color").map(text -> {
            if (!HEX.matcher(text).matches()) {
                throw new IllegalArgumentException("color must be #rrggbb, not '" + text + "'");
            }
            return Color.fromRGB(Integer.parseInt(text.substring(1), 16));
        });
        return new Params(name, count, Triple.read(params, "offset", 0, 16, false), speed,
                LocationSpec.read(params, "at", LocationSpec.Anchor.TARGET, true), color, size);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return Stages.now(executeResumable(params, context, ReadExecutor.INLINE));
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        Particle particle = verbs.particle(NamespacedKey.minecraft(
                params.particle().startsWith("minecraft:") ? params.particle().substring(10) : params.particle()));
        if (particle == null) {
            return Stages.failed("unknown particle '" + params.particle() + "'");
        }
        Quantity[] fixed = new Quantity[] {params.count(), params.speed(), params.size()};
        return Quantities.resolve(context, reads, fixed).thenCompose(q -> {
            var count = q[0].wholeValue(context);
            var speed = q[1].value(context);
            var size = q[2].value(context);
            if (count.isEmpty() || count.getAsInt() < 1 || count.getAsInt() > verbs.limits().particlesPerStep()) {
                return Stages.failed("count must be a whole number from 1 to " + verbs.limits().particlesPerStep());
            }
            if (speed.isEmpty() || size.isEmpty()) {
                return Stages.failed("speed and size must be numbers in range");
            }
            return resolveSpread(params, context, reads).thenCompose(spread -> params.at().resolveOffset(context, reads)
                    .thenCompose(at -> at.resolve(context, reads, verbs)).thenCompose(where -> {
                        if (where.location() == null) {
                            return Stages.failed(where.problem());
                        }
                        if (!WorldVerbs.within(context, WorldVerbs.PARTICLES, count.getAsInt(),
                                verbs.limits().particlesPerRun())) {
                            verbs.budgetSpent("particle", verbs.limits().particlesPerRun(), "particles");
                            return Stages.failed("this run has spawned its " + verbs.limits().particlesPerRun()
                                    + " particles");
                        }
                        Location location = where.location();
                        return verbs.regions().at(location, () -> spawn(particle, params, location, count.getAsInt(),
                                spread, speed.getAsDouble(), size.getAsDouble()));
                    }));
        });
    }

    private static CompletionStage<double[]> resolveSpread(Params params, LogicContext context, ReadExecutor reads) {
        if (params.offset().isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(new double[3]);
        }
        return params.offset().get().resolve(context, reads).thenApply(resolved -> {
            var values = resolved.values(context);
            return values.map(v -> new double[] {v.x(), v.y(), v.z()}).orElse(null);
        });
    }

    private ActionOutcome spawn(Particle particle, Params params, Location location, int count, double[] spread,
            double speed, double size) {
        if (spread == null) {
            return ActionOutcome.failed("offset must be numbers from 0 to 16");
        }
        Object data;
        Class<?> type = particle.getDataType();
        if (type == Void.class) {
            data = null;
        } else if (type == Particle.DustOptions.class) {
            data = new Particle.DustOptions(params.color().orElse(Color.RED), (float) size);
        } else if (type == Color.class) {
            data = params.color().orElse(Color.WHITE);
        } else if (type == Float.class) {
            data = 0f;
        } else if (type == Integer.class) {
            data = 0;
        } else {
            return ActionOutcome.failed("particle '" + params.particle() + "' needs " + type.getSimpleName()
                    + " data, which this action does not carry");
        }
        location.getWorld().spawnParticle(particle, location, count, spread[0], spread[1], spread[2], speed, data);
        return ActionOutcome.OK;
    }
}
