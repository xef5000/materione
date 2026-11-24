package dev.materione.plugin.runtime.behavior;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.scheduling.GlobalTimerScheduler;
import dev.materione.platform.scheduling.TimerWheel;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Rebuilds and dispatches all rule-document timers after each successful shadow swap. */
public final class ScheduledTriggerService implements AutoCloseable {

    static final int PLAYER_FANOUT_PER_TICK = 32;

    private final Plugin plugin;
    private final SchedulingGateway gateway;
    private final BehaviorService behavior;
    private final RuleEngine engine;
    private final Consumer<Diagnostic> report;
    private final Clock clock;
    private final ZoneId zone;
    private final GlobalTimerScheduler wheel;
    private final ArrayDeque<Pending> fanout = new ArrayDeque<>();
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
        fanout.clear();
        long armedGeneration = ++generation;
        WorldRules world = live == null ? null : live.view(WorldRules.KEY);
        List<ScheduledTriggerPlan.Entry> plan = ScheduledTriggerPlan.of(world, report);
        var registrations = new ArrayList<TimerWheel.Registration>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            ScheduledTriggerPlan.Entry entry = plan.get(i);
            String id = entry.rule().owner() + "#" + entry.rule().id() + "#" + i;
            Runnable due = () -> due(entry, armedGeneration);
            registrations.add(entry.interval() == null
                    ? TimerWheel.Registration.daily(id, entry.at(), entry.days(), due)
                    : TimerWheel.Registration.every(id, entry.interval(), due));
        }
        wheel.replace(registrations);
    }

    private void due(ScheduledTriggerPlan.Entry entry, long expectedGeneration) {
        if (expectedGeneration != generation) {
            return;
        }
        if (entry.audience() == ScheduledTriggerPlan.Audience.SERVER) {
            fireServer(entry);
            return;
        }
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            fanout.addLast(new Pending(entry, expectedGeneration, player));
        }
    }

    private void fireServer(ScheduledTriggerPlan.Entry entry) {
        var event = new BehaviorEvent(
                entry.rule().on().type(), "", behavior.serverContext(), null, null)
                .withFilterFacts(facts(entry));
        engine.fire(List.of(entry.rule()), event);
    }

    private void drainFanout() {
        for (Pending pending : takeFanout(fanout, PLAYER_FANOUT_PER_TICK)) {
            if (pending.generation != generation) {
                continue;
            }
            gateway.executeEntityLater(plugin, pending.player, 0,
                    () -> firePlayer(pending.entry, pending.generation, pending.player), null);
        }
    }

    static <T> List<T> takeFanout(ArrayDeque<T> queue, int limit) {
        var batch = new ArrayList<T>(Math.min(queue.size(), limit));
        while (!queue.isEmpty() && batch.size() < limit) {
            batch.add(queue.removeFirst());
        }
        return List.copyOf(batch);
    }

    private void firePlayer(ScheduledTriggerPlan.Entry entry, long expectedGeneration, Player player) {
        if (expectedGeneration != generation || !player.isOnline()) {
            return;
        }
        var root = behavior.baseRoot(player, player.getLocation());
        var event = new BehaviorEvent(
                entry.rule().on().type(), "", behavior.logicContext(root, player), null, player)
                .withFilterFacts(facts(entry));
        engine.fire(List.of(entry.rule()), event);
    }

    private FilterFacts facts(ScheduledTriggerPlan.Entry entry) {
        var values = new LinkedHashMap<String, String>();
        if (entry.interval() == null) {
            values.put("at", entry.value());
            String day = clock.instant().atZone(zone).getDayOfWeek()
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

    public boolean hasTimers() {
        return wheel.hasTimers();
    }

    @Override
    public void close() {
        generation++;
        fanout.clear();
        wheel.close();
    }

    private record Pending(ScheduledTriggerPlan.Entry entry, long generation, Player player) {}
}
