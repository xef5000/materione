package dev.materione.plugin.logic.world;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.Threading;
import dev.materione.plugin.logic.support.Triple;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The typed location value of the world verbs (logic.md §41): {@code at}. One value shape, read once, for every verb that
 * acts at a place.
 *
 * <pre>
 * at: event                              # where the trigger says it happened ({@code location.*}: the broken block, the struck entity)
 * at: player                             # the run's own player
 * at: target                             # the entity the action acts on (its {@code target:}, else the run's player)
 * at: {of: player, y: 1.5}               # an anchor and a world-axis offset in blocks
 * </pre>
 *
 * The offset reuses {@link Triple} (so each axis may be a template) and is bounded at {@link WorldVerbs#MAX_OFFSET} blocks.
 * {@code target} is accepted only by an action that has a target ({@code particle}); a verb with none says {@code player}.
 * An anchor that is a block coordinate (a custom block's or a broken block's {@code location}, whole numbers) means the
 * block's centre, so a particle or a drop appears in the block and not on its corner.
 *
 * @param anchor where the location starts
 * @param offset the world-axis offset from the anchor, when written
 */
public record LocationSpec(Anchor anchor, Optional<Triple> offset) {

    /** Where a location starts. */
    public enum Anchor { EVENT, PLAYER, TARGET }

    private static final Set<String> KEYS = Set.of("of", "x", "y", "z");

    /** A location resolved for one run, or the reason it could not be. */
    public record Result(Location location, String problem) {
        static Result of(Location location) {
            return new Result(location, null);
        }

        static Result failed(String problem) {
            return new Result(null, problem);
        }
    }

    /**
     * Reads {@code key} from {@code params}.
     *
     * @param fallback the anchor when the author writes none
     * @param admitsTarget whether the action has a target for {@code target} to name
     */
    public static LocationSpec read(ParamReader params, String key, Anchor fallback, boolean admitsTarget) {
        Optional<Object> raw = params.raw(key);
        LocationSpec spec;
        if (raw.isEmpty()) {
            spec = new LocationSpec(fallback, Optional.empty());
        } else if (raw.get() instanceof String word) {
            spec = new LocationSpec(anchor(key, word, admitsTarget), Optional.empty());
        } else if (raw.get() instanceof java.util.Map<?, ?>) {
            ParamReader section = params.section(key);
            for (String name : section.keys()) {
                if (!KEYS.contains(name)) {
                    throw new IllegalArgumentException(key + " has no key '" + name + "' (use of, x, y, z)");
                }
            }
            Anchor anchor = section.has("of") ? anchor(key + ".of", section.text("of"), admitsTarget) : fallback;
            boolean offsets = section.has("x") || section.has("y") || section.has("z");
            spec = new LocationSpec(anchor, offsets
                    ? Optional.of(Triple.readAxes(section, key, -WorldVerbs.MAX_OFFSET,
                            WorldVerbs.MAX_OFFSET, false, Set.of("of")))
                    : Optional.empty());
        } else {
            throw new IllegalArgumentException(key + " must be event, player, target, or {of, x, y, z}");
        }
        if (spec.anchor == Anchor.EVENT) {
            params.requireRoot("location", "'" + key + ": event' needs a trigger that carries a location");
        }
        return spec;
    }

    private static Anchor anchor(String key, String word, boolean admitsTarget) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "event" -> Anchor.EVENT;
            case "player" -> Anchor.PLAYER;
            case "target" -> {
                if (!admitsTarget) {
                    throw new IllegalArgumentException(key + ": this action has no target; use 'player' for the run's own player");
                }
                yield Anchor.TARGET;
            }
            default -> throw new IllegalArgumentException(key + " must be event, player or target, not '" + word + "'");
        };
    }

    /** This spec with the offset's templates rendered once; the location itself is read by {@link #resolve}. */
    public CompletionStage<LocationSpec> resolveOffset(LogicContext context, ReadExecutor reads) {
        if (offset.isEmpty()) {
            return CompletableFuture.completedFuture(this);
        }
        return offset.get().resolve(context, reads).thenApply(resolved -> new LocationSpec(anchor, Optional.of(resolved)));
    }

    /**
     * The location for this run. Call {@link #resolveOffset} first when the offset may hold templates. Reads the
     * anchor's location on the thread {@code reads} puts it on (the subject's region for an entity anchor).
     */
    public CompletionStage<Result> resolve(LogicContext context, ReadExecutor reads, WorldVerbs verbs) {
        Optional<Triple.Values> shift = offset.flatMap(triple -> triple.values(context));
        if (offset.isPresent() && shift.isEmpty()) {
            return CompletableFuture.completedFuture(Result.failed("an offset of the location is not a number in range"));
        }
        CompletionStage<Result> base = switch (anchor) {
            case EVENT -> CompletableFuture.completedFuture(eventLocation(context, verbs));
            case PLAYER -> entity(context.facet(Facets.PLAYER).map(player -> (Entity) player), reads,
                    "the run has no player");
            case TARGET -> entity(Subjects.entity(context), reads, "the action has nothing to act on");
        };
        return base.thenApply(result -> {
            if (result.location == null || shift.isEmpty()) {
                return result;
            }
            Triple.Values by = shift.get();
            return Result.of(result.location.clone().add(by.x(), by.y(), by.z()));
        });
    }

    private static CompletionStage<Result> entity(Optional<Entity> entity, ReadExecutor reads, String none) {
        if (entity.isEmpty()) {
            return CompletableFuture.completedFuture(Result.failed(none));
        }
        Entity subject = entity.get();
        return reads.on(Threading.PLAYER_REGION, subject::getLocation).thenApply(Result::of);
    }

    private static Result eventLocation(LogicContext context, WorldVerbs verbs) {
        Object x = context.value("location.x");
        Object y = context.value("location.y");
        Object z = context.value("location.z");
        Object world = context.value("location.world");
        if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) {
            return Result.failed("this event carries no location");
        }
        World target = world == null ? null : verbs.world(String.valueOf(world));
        if (target == null) {
            return Result.failed("the event's world '" + world + "' is not loaded");
        }
        boolean block = isWhole(x) && isWhole(y) && isWhole(z);
        double centre = block ? 0.5 : 0;
        return Result.of(new Location(target, nx.doubleValue() + centre, ny.doubleValue() + centre,
                nz.doubleValue() + centre));
    }

    private static boolean isWhole(Object number) {
        return number instanceof Integer || number instanceof Long || number instanceof Short;
    }

    /** The player the run belongs to, for a verb that acts as them. */
    public static Optional<Player> runPlayer(LogicContext context) {
        return context.facet(Facets.PLAYER);
    }
}
