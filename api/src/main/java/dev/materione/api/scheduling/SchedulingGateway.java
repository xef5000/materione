package dev.materione.api.scheduling;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * The single gateway for scheduling work (plugin architecture §2). All
 * world/entity mutation routes through here; on Folia these dispatch to the
 * correct region/entity scheduler, on Paper to the main thread. Code outside
 * {@code core.scheduling} never touches Bukkit schedulers — enforced by an
 * ArchUnit ban test, not review goodwill.
 */
public interface SchedulingGateway {

    /** Runs a task on the thread owning {@code location} (region thread on Folia). */
    void executeSync(Plugin plugin, Location location, Runnable task);

    /** Runs a task on the thread owning {@code entity}. */
    void executeEntity(Plugin plugin, Entity entity, Runnable task);

    /** Runs a task off the main/region threads. */
    void executeAsync(Plugin plugin, Runnable task);
}
