package dev.materione.core.scheduling;

import dev.materione.api.scheduling.SchedulingGateway;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Gateway implementation over Paper's native schedulers. On Folia these
 * dispatch to the owning region/entity thread; on Paper they route to the
 * main thread — exactly the routing a wrapper library would provide, without
 * a shaded dependency (see docs/architecture/build.md for the FoliaLib
 * deviation note).
 */
public final class SchedulingGatewayImpl implements SchedulingGateway {

    @Override
    public void executeSync(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    @Override
    public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
    }

    @Override
    public void executeAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }
}
