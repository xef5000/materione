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

    /**
     * Runs a task on the global region/main thread — the apply stage's
     * shadow-swap entry point (plugin.md §4.5: apply is the only pipeline
     * stage allowed on the main thread).
     */
    void executeGlobal(Plugin plugin, Runnable task);

    /**
     * Runs a task on the thread owning {@code entity} after {@code delayTicks} (0 = at the
     * entity's next opportunity, never inline). When the entity is removed before the task can
     * run (a player quits), {@code retired} runs instead, if it is non-null.
     *
     * <p>The server-backed implementation overrides this; the default exists so a test double that
     * never schedules ahead need not implement it, and it fails loudly rather than dropping the delay.
     *
     * @return a handle that cancels the task while it is still queued
     */
    default ScheduledHandle executeEntityLater(
            Plugin plugin, Entity entity, long delayTicks, Runnable task, Runnable retired) {
        throw new UnsupportedOperationException("this gateway cannot schedule ahead");
    }

    /**
     * Runs a task on the global region/main thread after {@code delayTicks} (0 = at the next
     * global tick, never inline).
     *
     * <p>Same default as {@link #executeEntityLater}.
     *
     * @return a handle that cancels the task while it is still queued
     */
    default ScheduledHandle executeGlobalLater(Plugin plugin, long delayTicks, Runnable task) {
        throw new UnsupportedOperationException("this gateway cannot schedule ahead");
    }
}
