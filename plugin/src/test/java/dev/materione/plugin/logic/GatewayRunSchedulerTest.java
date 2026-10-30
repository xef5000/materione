package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.Threading;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The run scheduler maps threads onto the gateway, and asks the server where a step may run. */
class GatewayRunSchedulerTest {

    private final List<String> calls = new ArrayList<>();
    private final boolean[] cancelled = {false};
    private final Plugin plugin = LogicTestSupport.owner("host");
    private final Entity player = entity();
    private boolean ownsEntity = true;
    private boolean onGlobal = true;

    private final SchedulingGateway gateway = new SchedulingGateway() {
        @Override
        public void executeSync(Plugin p, Location location, Runnable task) {
            throw new AssertionError("a run never uses the location scheduler");
        }

        @Override
        public void executeEntity(Plugin p, Entity entity, Runnable task) {
            throw new AssertionError("a run always schedules with a delay");
        }

        @Override
        public void executeAsync(Plugin p, Runnable task) {
            throw new AssertionError("a run never leaves the game threads");
        }

        @Override
        public void executeGlobal(Plugin p, Runnable task) {
            throw new AssertionError("a run always schedules with a delay");
        }

        @Override
        public ScheduledHandle executeEntityLater(
                Plugin p, Entity entity, long delayTicks, Runnable task, Runnable retired) {
            calls.add("entity+" + delayTicks + (retired != null ? " with retired" : ""));
            return () -> cancelled[0] = true;
        }

        @Override
        public ScheduledHandle executeGlobalLater(Plugin p, long delayTicks, Runnable task) {
            calls.add("global+" + delayTicks);
            return () -> cancelled[0] = true;
        }
    };

    private final GatewayRunScheduler scheduler = new GatewayRunScheduler(plugin, gateway,
            new GatewayRunScheduler.ThreadProbe() {
                @Override
                public boolean ownsEntity(Entity entity) {
                    return ownsEntity;
                }

                @Override
                public boolean onGlobalThread() {
                    return onGlobal;
                }
            });

    private static Entity entity() {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aStepCanRunInlineWhenTheServerSaysTheCallingThreadOwnsIt() {
        assertThat(scheduler.canRunInline(player, Threading.ANY)).isTrue();

        ownsEntity = true;
        assertThat(scheduler.canRunInline(player, Threading.PLAYER_REGION)).isTrue();
        ownsEntity = false;
        assertThat(scheduler.canRunInline(player, Threading.PLAYER_REGION)).isFalse();

        onGlobal = true;
        assertThat(scheduler.canRunInline(player, Threading.GLOBAL)).isTrue();
        onGlobal = false;
        assertThat(scheduler.canRunInline(player, Threading.GLOBAL)).isFalse();
    }

    @Test
    void aRunWithNoPlayerEntityIsOnTheThreadOfTheEventThatFiredIt() {
        ownsEntity = false;

        assertThat(scheduler.canRunInline(null, Threading.PLAYER_REGION)).isTrue();
        assertThat(scheduler.canRunInline("not an entity", Threading.PLAYER_REGION)).isTrue();
    }

    @Test
    void thePlayersThreadSchedulesOnTheEntitySchedulerWithARetiredCallback() {
        var handle = scheduler.schedule(player, Threading.PLAYER_REGION, 40, () -> { }, () -> { });

        assertThat(calls).containsExactly("entity+40 with retired");
        handle.cancel();
        assertThat(cancelled[0]).isTrue();
    }

    @Test
    void theGlobalThreadSchedulesGloballyEvenForAPlayerRun() {
        scheduler.schedule(player, Threading.GLOBAL, 0, () -> { }, () -> { });

        assertThat(calls).containsExactly("global+0");
    }

    @Test
    void aRunWithNoEntityFallsBackToTheGlobalScheduler() {
        scheduler.schedule(null, Threading.PLAYER_REGION, 5, () -> { }, () -> { });

        assertThat(calls).containsExactly("global+5");
    }
}
