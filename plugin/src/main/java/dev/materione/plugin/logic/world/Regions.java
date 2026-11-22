package dev.materione.plugin.logic.world;

import dev.materione.api.scheduling.SchedulingGateway;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Where a world mutation may run (logic.md §41, D8): every block, particle and drop a verb makes is done on the thread
 * that owns its location. On Paper every location is owned by the one thread, so {@link #at} is an inline call; on
 * Folia a location in another region is hopped to through the scheduling gateway and the stage completes there.
 *
 * <p>This is a seam so a test can say "this location belongs to another region" and watch the work go to the gateway.
 */
public interface Regions {

    /** Whether the calling thread owns {@code location}. */
    boolean ownedHere(Location location);

    /**
     * Whether the calling thread owns {@code entity}. The default asks for its location, which a server overrides with the
     * entity form (reading an entity's location from another region is itself unsafe on Folia).
     */
    default boolean ownedHere(Entity entity) {
        return ownedHere(entity.getLocation());
    }

    /**
     * Runs {@code task} on the thread that owns {@code location}: inline when this thread already does, else on that
     * region's thread, completing the stage there. A task that throws fails the stage; it is never lost.
     */
    <T> CompletionStage<T> at(Location location, Supplier<T> task);

    /** The server's answer: ownership from {@code Bukkit}, a hop through {@code gateway}. */
    static Regions server(Plugin plugin, SchedulingGateway gateway) {
        return new Regions() {
            @Override
            public boolean ownedHere(Location location) {
                return Bukkit.isOwnedByCurrentRegion(location);
            }

            @Override
            public boolean ownedHere(Entity entity) {
                return Bukkit.isOwnedByCurrentRegion(entity);
            }

            @Override
            public <T> CompletionStage<T> at(Location location, Supplier<T> task) {
                if (ownedHere(location)) {
                    try {
                        return CompletableFuture.completedFuture(task.get());
                    } catch (RuntimeException failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                }
                CompletableFuture<T> done = new CompletableFuture<>();
                gateway.executeSync(plugin, location, () -> {
                    try {
                        done.complete(task.get());
                    } catch (RuntimeException failure) {
                        done.completeExceptionally(failure);
                    }
                });
                return done;
            }
        };
    }
}
