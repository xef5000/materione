package dev.materione.plugin.logic.target;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetResolution;
import dev.materione.api.logic.TargetResolver;
import dev.materione.api.logic.TargetSpec;
import dev.materione.api.logic.Threading;
import dev.materione.platform.entity.EntityPalette;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Resolves a {@code target:} on the running server (logic.md §39). The five forms:
 *
 * <ul>
 *   <li>{@code event.target}: the entity the trigger carries ({@link Facets#EVENT_TARGET}), refused when it is gone;
 *   <li>{@code arg: name} and {@code player: ...}: an online player by name or uuid (offline is a refusal in v1);
 *   <li>{@code nearby}: a bounded search around the run's player, on the thread that owns it.
 * </ul>
 *
 * <p>A refusal is a {@link TargetResolution} with a reason, never an exception: the action fails with it, which a
 * {@code require} and the trace see. Resolution reads the world only through the owner's thread ({@code reads}) or
 * through lookups that are safe from any thread (the online player list), and it never mutates anything; the action
 * that runs on the result is dispatched to each subject's own region by the engine (D8).
 */
public final class BukkitTargetResolver implements TargetResolver {

    @Override
    public CompletionStage<TargetResolution> resolve(
            TargetSpec spec, Set<TargetKind> admits, LogicContext context, ReadExecutor reads) {
        try {
            return switch (spec) {
                case TargetSpec.Self ignored -> done(TargetResolution.none("self needs no resolution"));
                case TargetSpec.Event ignored -> done(event(context, admits));
                case TargetSpec.Arg arg -> done(argument(arg, context, admits));
                case TargetSpec.Player player -> player.name().renderResumable(context, reads)
                        .thenApply(name -> named(name, admits));
                case TargetSpec.Nearby nearby -> nearby(nearby, admits, context, reads);
            };
        } catch (RuntimeException failure) {
            return done(TargetResolution.none("could not be resolved: " + failure.getMessage()));
        }
    }

    // --- single subjects ----------------------------------------------------------------

    private static TargetResolution event(LogicContext context, Set<TargetKind> admits) {
        Entity entity = context.facet(Facets.EVENT_TARGET).orElse(null);
        if (entity == null) {
            return TargetResolution.none("this event carries no target");
        }
        boolean here = entity instanceof Player player ? player.isOnline() : entity.isValid();
        return here ? admitted(entity, admits) : TargetResolution.none("the target is no longer in the world");
    }

    private static TargetResolution argument(TargetSpec.Arg arg, LogicContext context, Set<TargetKind> admits) {
        Object value = context.value("args." + arg.name());
        if (value == null) {
            return TargetResolution.none("argument '" + arg.name() + "' is not set");
        }
        return named(String.valueOf(value), admits);
    }

    /** An online player by name (matched ignoring case, never a prefix) or by uuid. */
    static TargetResolution named(String text, Set<TargetKind> admits) {
        String name = text == null ? "" : text.trim();
        if (name.isEmpty()) {
            return TargetResolution.none("the player name is empty");
        }
        Player player = null;
        if (name.length() == 36 && name.indexOf('-') == 8) {
            try {
                player = Bukkit.getPlayer(UUID.fromString(name));
            } catch (IllegalArgumentException notAUuid) {
                player = null;
            }
        }
        if (player == null) {
            player = Bukkit.getPlayerExact(name);
        }
        if (player == null) {
            return TargetResolution.none("no player named '" + name + "' is online");
        }
        return admitted(player, admits);
    }

    private static TargetResolution admitted(Entity entity, Set<TargetKind> admits) {
        var target = new EntityTarget(entity);
        if (!admits.contains(target.kind())) {
            return TargetResolution.none("the action cannot act on a " + target.kind().id() + " (" + target.describe() + ")");
        }
        return TargetResolution.of(target);
    }

    // --- the bounded selector -----------------------------------------------------------

    private static CompletionStage<TargetResolution> nearby(
            TargetSpec.Nearby nearby, Set<TargetKind> admits, LogicContext context, ReadExecutor reads) {
        Player around = context.facet(Facets.PLAYER).orElse(null);
        if (around == null) {
            return done(TargetResolution.none("the run has no player to search around"));
        }
        List<String> known = EntityPalette.entityTypes();
        if (nearby.type() != null && !known.isEmpty() && !known.contains(nearby.type())) {
            return done(TargetResolution.none("'" + nearby.type() + "' is not an entity type on this server"));
        }
        return reads.on(Threading.PLAYER_REGION, () -> search(around, nearby, admits));
    }

    /** Runs on the thread that owns {@code around}. */
    private static TargetResolution search(Player around, TargetSpec.Nearby nearby, Set<TargetKind> admits) {
        double radius = nearby.radius();
        List<Entity> candidates;
        try {
            candidates = around.getNearbyEntities(radius, radius, radius);
        } catch (RuntimeException otherRegion) {
            // on Folia the search box must lie in the region the thread owns
            return TargetResolution.none("the search area is not owned by this region");
        }
        Location center = around.getLocation();
        double limit = radius * radius;
        var found = new ArrayList<Entity>();
        for (Entity entity : candidates) {
            // a mob that was just killed is still in the box while it plays its death: it is not a target
            if (entity == around || !entity.isValid() || entity.getWorld() != center.getWorld()
                    || entity.getLocation().distanceSquared(center) > limit) {
                continue;
            }
            TargetKind kind = entity instanceof Player ? TargetKind.PLAYER : TargetKind.ENTITY;
            if (!admits.contains(kind)) {
                continue;
            }
            if (entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            // without a type a selector means the living things around; with one it means exactly that type
            if (nearby.type() == null ? !(entity instanceof LivingEntity) : !nearby.type().equals(EntityPalette.typeOf(entity))) {
                continue;
            }
            found.add(entity);
        }
        found.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center)));
        List<ResolvedTarget> chosen = found.stream().limit(nearby.limit()).<ResolvedTarget>map(EntityTarget::new).toList();
        return TargetResolution.of(chosen, "no " + (nearby.type() == null ? "entity" : nearby.type()) + " within "
                + (radius == Math.rint(radius) ? Long.toString((long) radius) : Double.toString(radius)) + " blocks");
    }

    private static CompletionStage<TargetResolution> done(TargetResolution resolution) {
        return CompletableFuture.completedFuture(resolution);
    }
}
