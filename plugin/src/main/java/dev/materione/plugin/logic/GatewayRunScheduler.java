package dev.materione.plugin.logic;

import dev.materione.api.logic.Threading;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.run.RunScheduler;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The {@link RunScheduler} over the Folia-safe thread gateway (plugin.md §2): a parked run comes
 * back on the player's region thread (the entity scheduler, which also drops the run if the player
 * leaves first) or on the global region thread. It never touches a Bukkit scheduler itself; the
 * gateway is the only door, and the ban test keeps it so.
 *
 * <p>"Which thread am I on" is answered by the server's ownership checks. On Paper everything is
 * one thread, so every answer is true and a flow runs entirely inline; on Folia a step for another
 * region (or the console) is hopped to.
 */
public final class GatewayRunScheduler implements RunScheduler {

    /** The server's answers to "may this thread touch that?", split out so a test can stand in. */
    public interface ThreadProbe {

        /** Whether the calling thread owns {@code entity}. */
        boolean ownsEntity(Entity entity);

        /** Whether the calling thread is the global region (main) thread. */
        boolean onGlobalThread();

        /** The probe backed by the running server. */
        ThreadProbe SERVER = new ThreadProbe() {
            @Override
            public boolean ownsEntity(Entity entity) {
                return Bukkit.isOwnedByCurrentRegion(entity);
            }

            @Override
            public boolean onGlobalThread() {
                return Bukkit.isGlobalTickThread();
            }
        };
    }

    private final Plugin plugin;
    private final SchedulingGateway gateway;
    private final ThreadProbe probe;
    private final Consumer<UUID> retired;

    public GatewayRunScheduler(Plugin plugin, SchedulingGateway gateway) {
        this(plugin, gateway, ThreadProbe.SERVER);
    }

    public GatewayRunScheduler(Plugin plugin, SchedulingGateway gateway, ThreadProbe probe) {
        this(plugin, gateway, probe, ignored -> {});
    }

    public GatewayRunScheduler(
            Plugin plugin, SchedulingGateway gateway, ThreadProbe probe, Consumer<UUID> retired) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.retired = Objects.requireNonNull(retired, "retired");
    }

    @Override
    public boolean canRunInline(Object anchor, Threading thread) {
        return switch (thread) {
            case ANY -> true;
            // a run with no player entity (a block broken by an explosion) has no region to hop to:
            // it is on the thread of the event that fired it
            case PLAYER_REGION -> !(anchor instanceof Entity entity) || probe.ownsEntity(entity);
            case GLOBAL -> probe.onGlobalThread();
        };
    }

    @Override
    public Threading currentOwner(Object anchor) {
        if (probe.onGlobalThread()) {
            return Threading.GLOBAL;
        }
        return anchor instanceof Entity entity && probe.ownsEntity(entity) ? Threading.PLAYER_REGION : null;
    }

    @Override
    public Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped) {
        if (thread != Threading.GLOBAL && anchor instanceof Entity entity) {
            var handle = gateway.executeEntityLater(plugin, entity, delayTicks, task, () -> {
                if (entity instanceof Player player) {
                    retired.accept(player.getUniqueId());
                }
                dropped.run();
            });
            return handle::cancel;
        }
        var handle = gateway.executeGlobalLater(plugin, delayTicks, task);
        return handle::cancel;
    }
}
