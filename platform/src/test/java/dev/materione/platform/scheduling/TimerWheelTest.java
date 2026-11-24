package dev.materione.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.scheduling.TimerInterval;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class TimerWheelTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    @Test
    void aTickIntervalFiresOnceAfterDriftAndSkipsMissedOccurrences() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var fired = new AtomicInteger();
        var wheel = new TimerWheel(clock, UTC, failure -> {
            throw new AssertionError(failure);
        });
        wheel.replace(List.of(TimerWheel.Registration.every(
                "ticks", TimerInterval.parse(20), fired::incrementAndGet)), 0);

        wheel.tick(19);
        assertThat(fired).hasValue(0);
        wheel.tick(65);
        assertThat(fired).hasValue(1);
        wheel.tick(79);
        assertThat(fired).hasValue(1);
        wheel.tick(80);
        assertThat(fired).hasValue(2);
    }

    @Test
    void aDurationAlsoFiresOnceAfterClockDrift() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var fired = new AtomicInteger();
        var wheel = new TimerWheel(clock, UTC, failure -> {
            throw new AssertionError(failure);
        });
        wheel.replace(List.of(TimerWheel.Registration.every(
                "duration", TimerInterval.parse("1s"), fired::incrementAndGet)), 0);

        clock.advance(10);
        wheel.tick(200);
        assertThat(fired).hasValue(1);
        wheel.tick(201);
        assertThat(fired).hasValue(1);
        clock.advance(1);
        wheel.tick(220);
        assertThat(fired).hasValue(2);
    }

    @Test
    void replacingTheWheelResetsDeadlinesInsteadOfReplayingTheOldShadow() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var fired = new AtomicInteger();
        var wheel = new TimerWheel(clock, UTC, failure -> {
            throw new AssertionError(failure);
        });
        wheel.replace(List.of(TimerWheel.Registration.every(
                "old", TimerInterval.parse(20), fired::incrementAndGet)), 0);
        wheel.tick(19);
        wheel.replace(List.of(TimerWheel.Registration.every(
                "new", TimerInterval.parse(40), fired::incrementAndGet)), 19);

        wheel.tick(58);
        assertThat(fired).hasValue(0);
        wheel.tick(59);
        assertThat(fired).hasValue(1);
    }

    @Test
    void aDailyTimeHonoursDaysAndTheServerZoneWithoutReplay() {
        var clock = new MutableClock(Instant.parse("2026-01-05T09:59:00Z")); // Monday
        var fired = new AtomicInteger();
        var wheel = new TimerWheel(clock, UTC, failure -> {
            throw new AssertionError(failure);
        });
        wheel.replace(List.of(TimerWheel.Registration.daily(
                "reset", LocalTime.of(10, 0), Set.of(DayOfWeek.MONDAY), fired::incrementAndGet)), 0);

        wheel.tick(1);
        assertThat(fired).hasValue(0);
        clock.advance(60);
        wheel.tick(2);
        assertThat(fired).hasValue(1);
        clock.advance(7 * 24 * 60 * 60);
        wheel.tick(3);
        assertThat(fired).hasValue(2);
    }

    @Test
    void aDriftedDailyCallbackReceivesTheLogicalOccurrenceItMissed() {
        var clock = new MutableClock(Instant.parse("2026-01-05T05:00:00Z")); // Monday
        var scheduledFor = new AtomicReference<Instant>();
        var wheel = new TimerWheel(clock, UTC, failure -> {
            throw new AssertionError(failure);
        });
        wheel.replace(List.of(TimerWheel.Registration.daily(
                "reset", LocalTime.of(6, 0), Set.of(DayOfWeek.MONDAY), scheduledFor::set)), 0);

        clock.set(Instant.parse("2026-01-06T07:00:00Z"));
        wheel.tick(1);

        assertThat(scheduledFor.get()).isEqualTo(Instant.parse("2026-01-05T06:00:00Z"));
    }

    @Test
    void oneGlobalWakeupOwnsManyTimersAndNoTimersScheduleNothing() {
        var gateway = new FakeGateway();
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var after = new AtomicInteger();
        var scheduler = new GlobalTimerScheduler(
                plugin(), gateway, clock, UTC, failure -> {
                    throw new AssertionError(failure);
                }, after::incrementAndGet);

        scheduler.replace(List.of());
        assertThat(gateway.pending()).isEmpty();
        scheduler.replace(List.of(
                TimerWheel.Registration.every("a", TimerInterval.parse(20), () -> {}),
                TimerWheel.Registration.every("b", TimerInterval.parse("1s"), () -> {})));
        assertThat(gateway.pending()).hasSize(1);

        gateway.runNext();
        assertThat(gateway.pending()).hasSize(1);
        assertThat(after).hasValue(1);
        scheduler.close();
        assertThat(gateway.pending()).isEmpty();
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                TimerWheelTest.class.getClassLoader(), new Class<?>[] { Plugin.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakePlugin";
                    default -> null;
                });
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeGateway implements SchedulingGateway {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final ArrayDeque<Cancellable> handles = new ArrayDeque<>();

        @Override
        public void executeSync(Plugin plugin, Location location, Runnable task) {
            task.run();
        }

        @Override
        public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
            task.run();
        }

        @Override
        public void executeAsync(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public void executeGlobal(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public ScheduledHandle executeGlobalLater(Plugin plugin, long delayTicks, Runnable task) {
            tasks.addLast(task);
            var handle = new Cancellable(task, tasks);
            handles.addLast(handle);
            return handle::cancel;
        }

        void runNext() {
            tasks.removeFirst().run();
            handles.removeFirst();
        }

        List<Runnable> pending() {
            return List.copyOf(tasks);
        }

        private static final class Cancellable {
            private final Runnable task;
            private final ArrayDeque<Runnable> tasks;

            private Cancellable(Runnable task, ArrayDeque<Runnable> tasks) {
                this.task = task;
                this.tasks = tasks;
            }

            void cancel() {
                tasks.remove(task);
            }
        }
    }
}
