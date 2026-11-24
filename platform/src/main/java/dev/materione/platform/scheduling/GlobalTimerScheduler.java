package dev.materione.platform.scheduling;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

/** The one Folia global-scheduler wake-up that advances every live timer. */
public final class GlobalTimerScheduler implements AutoCloseable {

    private final Plugin plugin;
    private final SchedulingGateway gateway;
    private final Clock clock;
    private final ZoneId zone;
    private final Consumer<Throwable> failures;
    private final Runnable afterTick;
    private TimerWheel wheel;
    private ScheduledHandle handle = ScheduledHandle.NONE;
    private long generation;
    private long serverTick;
    private boolean closed;

    public GlobalTimerScheduler(
            Plugin plugin,
            SchedulingGateway gateway,
            Clock clock,
            ZoneId zone,
            Consumer<Throwable> failures,
            Runnable afterTick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.failures = failures == null ? ignored -> {} : failures;
        this.afterTick = afterTick == null ? () -> {} : afterTick;
        this.wheel = new TimerWheel(clock, zone, this.failures);
    }

    public void replace(List<TimerWheel.Registration> registrations) {
        if (closed) {
            return;
        }
        handle.cancel();
        handle = ScheduledHandle.NONE;
        long currentGeneration = ++generation;
        serverTick = 0;
        wheel = new TimerWheel(clock, zone, failures);
        wheel.replace(registrations, serverTick);
        if (wheel.size() > 0) {
            arm(currentGeneration);
        }
    }

    public boolean hasTimers() {
        return wheel.size() > 0;
    }

    private void arm(long expectedGeneration) {
        handle = gateway.executeGlobalLater(plugin, 1, () -> pulse(expectedGeneration));
    }

    private void pulse(long expectedGeneration) {
        if (closed || expectedGeneration != generation || wheel.size() == 0) {
            return;
        }
        serverTick++;
        wheel.tick(serverTick);
        try {
            afterTick.run();
        } catch (Throwable t) {
            failures.accept(t);
        }
        if (!closed && expectedGeneration == generation && wheel.size() > 0) {
            arm(expectedGeneration);
        }
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        handle.cancel();
        handle = ScheduledHandle.NONE;
        wheel = new TimerWheel(clock, zone, failures);
    }
}
