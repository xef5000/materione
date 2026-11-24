package dev.materione.plugin.runtime.behavior;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.scheduling.GlobalTimerScheduler;
import dev.materione.platform.scheduling.TimerWheel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Rebuilds and dispatches all rule-document timers after each successful shadow swap. */
public final class ScheduledTriggerService implements AutoCloseable {

    static final int PLAYER_FANOUT_PER_TICK = 32;
    static final int PLAYER_FANOUT_CAPACITY = PLAYER_FANOUT_PER_TICK * 20;

    private final Plugin plugin;
    private final SchedulingGateway gateway;
    private final BehaviorService behavior;
    private final RuleEngine engine;
    private final Consumer<Diagnostic> report;
    private final Clock clock;
    private final ZoneId zone;
    private final GlobalTimerScheduler wheel;
    private FanoutBuffer<Pending> fanout = new FanoutBuffer<>(PLAYER_FANOUT_CAPACITY);
    private int rejectedFanoutWaves;
    private long rejectedFanoutPlayers;
    private long generation;

    public ScheduledTriggerService(
            Plugin plugin,
            SchedulingGateway gateway,
            BehaviorService behavior,
            RuleEngine engine,
            Supplier<RuntimeRegistries> registries,
            Consumer<Diagnostic> report) {
        this(plugin, gateway, behavior, engine, registries, report, Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    ScheduledTriggerService(
            Plugin plugin,
            SchedulingGateway gateway,
            BehaviorService behavior,
            RuleEngine engine,
            Supplier<RuntimeRegistries> registries,
            Consumer<Diagnostic> report,
            Clock clock,
            ZoneId zone) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.behavior = behavior;
        this.engine = engine;
        this.report = report;
        this.clock = clock;
        this.zone = zone;
        this.wheel = new GlobalTimerScheduler(
                plugin, gateway, clock, zone, this::failed, this::drainFanout);
        rebuild(registries.get());
    }

    public void refreshAfterSwap(RuntimeRegistries ignoredPrevious, RuntimeRegistries live) {
        rebuild(live);
    }

    private void rebuild(RuntimeRegistries live) {
        fanout.close();
        fanout = new FanoutBuffer<>(PLAYER_FANOUT_CAPACITY);
        rejectedFanoutWaves = 0;
        rejectedFanoutPlayers = 0;
        long armedGeneration = ++generation;
        WorldRules world = live == null ? null : live.view(WorldRules.KEY);
        List<ScheduledTriggerPlan.Entry> plan = ScheduledTriggerPlan.of(world, report);
        var registrations = new ArrayList<TimerWheel.Registration>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            ScheduledTriggerPlan.Entry entry = plan.get(i);
            String id = entry.rule().owner() + "#" + entry.rule().id() + "#" + i;
            Runnable due = () -> due(entry, armedGeneration, null);
            registrations.add(entry.interval() == null
                    ? TimerWheel.Registration.daily(id, entry.at(), entry.days(),
                            scheduledFor -> due(entry, armedGeneration, scheduledFor))
                    : TimerWheel.Registration.every(id, entry.interval(), due));
        }
        wheel.replace(registrations);
    }

    private void due(ScheduledTriggerPlan.Entry entry, long expectedGeneration, Instant scheduledFor) {
        if (expectedGeneration != generation) {
            return;
        }
        if (entry.audience() == ScheduledTriggerPlan.Audience.SERVER) {
            fireServer(entry, scheduledFor);
            return;
        }
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        var pending = players.stream()
                .map(player -> new Pending(entry, expectedGeneration, scheduledFor, player))
                .toList();
        if (!fanout.offerAll(pending)) {
            rejectedFanoutWaves++;
            rejectedFanoutPlayers += players.size();
        }
    }

    private void fireServer(ScheduledTriggerPlan.Entry entry, Instant scheduledFor) {
        var event = new BehaviorEvent(
                entry.rule().on().type(), "", behavior.serverContext(), null, null)
                .withFilterFacts(facts(entry, scheduledFor));
        engine.fire(List.of(entry.rule()), event);
    }

    private void drainFanout() {
        for (Pending pending : fanout.take(PLAYER_FANOUT_PER_TICK)) {
            if (pending.generation != generation) {
                fanout.releaseOne();
                continue;
            }
            FanoutLease<Pending> lease = fanout.lease();
            try {
                lease.start(gateway.executeEntityLater(plugin, pending.player, 0, () -> {
                    try {
                        firePlayer(pending.entry, pending.generation, pending.scheduledFor, pending.player);
                    } finally {
                        lease.complete();
                    }
                }, lease::complete));
            } catch (Throwable failure) {
                lease.complete();
                failed(failure);
            }
        }
        if (rejectedFanoutWaves > 0) {
            report.accept(fanoutOverloadDiagnostic(rejectedFanoutWaves, rejectedFanoutPlayers));
            rejectedFanoutWaves = 0;
            rejectedFanoutPlayers = 0;
        }
    }

    static <T> List<T> takeFanout(ArrayDeque<T> queue, int limit) {
        var batch = new ArrayList<T>(Math.min(queue.size(), limit));
        while (!queue.isEmpty() && batch.size() < limit) {
            batch.add(queue.removeFirst());
        }
        return List.copyOf(batch);
    }

    private void firePlayer(
            ScheduledTriggerPlan.Entry entry, long expectedGeneration, Instant scheduledFor, Player player) {
        if (expectedGeneration != generation || !player.isOnline()) {
            return;
        }
        var root = behavior.baseRoot(player, player.getLocation());
        var event = new BehaviorEvent(
                entry.rule().on().type(), "", behavior.logicContext(root, player), null, player)
                .withFilterFacts(facts(entry, scheduledFor));
        engine.fire(List.of(entry.rule()), event);
    }

    FilterFacts facts(ScheduledTriggerPlan.Entry entry, Instant scheduledFor) {
        var values = new LinkedHashMap<String, String>();
        if (entry.interval() == null) {
            values.put("at", entry.value());
            Instant logicalFire = scheduledFor == null ? clock.instant() : scheduledFor;
            String day = logicalFire.atZone(zone).getDayOfWeek()
                    .name().toLowerCase(Locale.ROOT);
            values.put("days", day);
        } else {
            values.put("interval", entry.value());
            values.put("for", entry.audienceValue());
        }
        return FilterFacts.of(values);
    }

    private void failed(Throwable failure) {
        report.accept(Diagnostic.warn(
                DiagnosticCodes.ACTION_FAILED,
                "scheduled trigger dispatch failed: " + failure));
    }

    static Diagnostic fanoutOverloadDiagnostic(int waves, long players) {
        return new Diagnostic(
                DiagnosticCodes.TIMER_FANOUT_OVERLOADED,
                Severity.WARN,
                "scheduled each_player fan-out refused " + waves + " whole firing wave(s) for " + players
                        + " player run(s); the bounded queue holds at most "
                        + PLAYER_FANOUT_CAPACITY + " admitted runs",
                null, null,
                "reduce simultaneous one-second each_player timers or the online player count");
    }

    public boolean hasTimers() {
        return wheel.hasTimers();
    }

    @Override
    public void close() {
        generation++;
        fanout.close();
        wheel.close();
    }

    private record Pending(
            ScheduledTriggerPlan.Entry entry, long generation, Instant scheduledFor, Player player) {}

    static final class FanoutBuffer<T> {
        private final int capacity;
        private final ArrayDeque<T> pending = new ArrayDeque<>();
        private final AtomicInteger admitted = new AtomicInteger();
        private final Set<FanoutLease<T>> leases = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        FanoutBuffer(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
        }

        boolean offerAll(List<T> values) {
            if (values.isEmpty()) {
                return true;
            }
            if (closed || values.size() > capacity) {
                return false;
            }
            while (true) {
                int before = admitted.get();
                if (before > capacity - values.size()) {
                    return false;
                }
                if (admitted.compareAndSet(before, before + values.size())) {
                    pending.addAll(values);
                    return true;
                }
            }
        }

        List<T> take(int limit) {
            return takeFanout(pending, limit);
        }

        FanoutLease<T> lease() {
            var lease = new FanoutLease<>(this);
            leases.add(lease);
            return lease;
        }

        void releaseOne() {
            if (admitted.get() > 0) {
                admitted.decrementAndGet();
            }
        }

        int capacity() {
            return capacity;
        }

        int size() {
            return pending.size();
        }

        int admitted() {
            return admitted.get();
        }

        void complete(FanoutLease<T> lease) {
            if (leases.remove(lease)) {
                admitted.decrementAndGet();
            }
        }

        void close() {
            closed = true;
            pending.clear();
            for (FanoutLease<T> lease : leases) {
                lease.cancel();
            }
            leases.clear();
            admitted.set(0);
        }
    }

    static final class FanoutLease<T> {
        private final FanoutBuffer<T> owner;
        private final AtomicReference<ScheduledHandle> handle = new AtomicReference<>(ScheduledHandle.NONE);
        private final AtomicBoolean complete = new AtomicBoolean();

        FanoutLease(FanoutBuffer<T> owner) {
            this.owner = owner;
        }

        void start(ScheduledHandle scheduled) {
            handle.set(scheduled == null ? ScheduledHandle.NONE : scheduled);
            if (complete.get()) {
                handle.get().cancel();
            }
        }

        void complete() {
            if (complete.compareAndSet(false, true)) {
                owner.complete(this);
            }
        }

        void cancel() {
            handle.get().cancel();
            complete();
        }
    }
}
