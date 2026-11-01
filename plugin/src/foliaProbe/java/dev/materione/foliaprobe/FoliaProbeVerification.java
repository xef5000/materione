package dev.materione.foliaprobe;

import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.run.RunOwner;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.TraceEvent;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.logic.GatewayRunScheduler;
import dev.materione.plugin.logic.action.SetVarAction;
import dev.materione.plugin.logic.currency.CurrencyReads;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Disposable, opt-in native Folia evidence; no task waits for another scheduler. */
final class FoliaProbeVerification {
    private static final NamespacedId TOTAL = NamespacedId.of("probe", "folia250_total");
    private static final int EACH = 5000;

    static void rmw(FoliaProbe addon) {
        MaterionePlugin plugin = (MaterionePlugin) Bukkit.getPluginManager().getPlugin("Materione");
        World world = Bukkit.getWorlds().get(0);
        var variables = plugin.variableService();
        var declaration = variables.declaration(TOTAL).orElseThrow();
        var reset = variables.api().set(ActorContext.console(), TOTAL, null, 0d);
        if (!reset.ok()) {
            addon.getLogger().severe("MTR250-RMW-FAIL cannot reset declared counter");
            return;
        }
        var gateway = new GatewayRunScheduler(addon, plugin.scheduling());
        var engine = new FlowInterpreter(gateway, new RunBudget(10, EACH * 3, 2),
                diagnostic -> addon.getLogger().severe("MTR250-RMW-DIAGNOSTIC " + diagnostic.message()));
        var action = new SetVarAction();
        var params = new SetVarAction.Params(new SetVarAction.PersistentTarget(declaration),
                VariableOp.ADD, new SetVarAction.Literal(1));
        var path = StepPath.of("folia250");
        var rule = new Rule(TOTAL, "folia250-rmw", new EventSelector("probe", Optional.empty()),
                new AllCond(List.of()), List.of(new ActionStep<>(path, "set_var", action, params, 1, 0)),
                RuleOptions.none(), RuleFilters.none(), path);
        var completed = new AtomicInteger();
        var writes = new AtomicInteger();
        var faults = new AtomicInteger();
        var active = new AtomicInteger();
        var overlap = new AtomicBoolean();
        var times = new AtomicLongArray(4);
        var threads = new AtomicReferenceArray<String>(2);
        VariableBinding delegate = variables.bind(null);
        VariableBinding binding = new VariableBinding() {
            @Override public Optional<VariableDeclaration> declaration(NamespacedId id) {
                return delegate.declaration(id);
            }
            @Override public Optional<Object> stored(VariableDeclaration var) {
                return delegate.stored(var);
            }
            @Override public boolean store(VariableDeclaration var, Object value) {
                return delegate.store(var, value);
            }
            @Override public VariableOps.Outcome mutate(VariableDeclaration var, VariableOp op, Object value) {
                if (!Bukkit.isOwnedByCurrentRegion(world, 0, 0)
                        && !Bukkit.isOwnedByCurrentRegion(world, 1024, 0)) faults.incrementAndGet();
                writes.incrementAndGet();
                return delegate.mutate(var, op, value);
            }
        };
        engine.traceTo(event -> {
            if (event.type() == TraceEvent.Type.STEP_FAILED) faults.incrementAndGet();
            if (event.type() == TraceEvent.Type.RUN_ENDED && completed.incrementAndGet() == EACH * 2) {
                Bukkit.getGlobalRegionScheduler().execute(addon, () -> {
                    Object total = variables.api().get(TOTAL, null).orElse(null);
                    boolean distinct = threads.get(0) != null && threads.get(1) != null
                            && !threads.get(0).equals(threads.get(1));
                    boolean ok = Double.valueOf(EACH * 2d).equals(total) && writes.get() == EACH * 2
                            && faults.get() == 0 && overlap.get() && distinct;
                    addon.getLogger().info("MTR250-RMW " + (ok ? "PASS" : "FAIL")
                            + " total=" + total + " expected=" + (EACH * 2) + " writes=" + writes.get()
                            + " faults=" + faults.get() + " overlap=" + overlap.get()
                            + " threads=" + threads.get(0) + "|" + threads.get(1)
                            + " windows=" + times.get(0) + "-" + times.get(1)
                            + "|" + times.get(2) + "-" + times.get(3));
                });
            }
        });
        CompletableFuture.allOf(world.getChunkAtAsync(0, 0), world.getChunkAtAsync(1024, 0))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        addon.getLogger().severe("MTR250-RMW-FAIL chunk load " + failure);
                        return;
                    }
                    var ready = new AtomicInteger();
                    Runnable launch = () -> {
                    for (int origin = 0; origin < 2; origin++) {
                        final int index = origin;
                        int chunkX = origin == 0 ? 0 : 1024;
                        Bukkit.getRegionScheduler().runDelayed(addon, world, chunkX, 0, scheduled -> {
                            threads.set(index, Thread.currentThread().getName());
                            if (!Bukkit.isOwnedByCurrentRegion(world, chunkX, 0)) faults.incrementAndGet();
                            if (Bukkit.isOwnedByCurrentRegion(world, index == 0 ? 1024 : 0, 0)) faults.incrementAndGet();
                            times.set(index * 2, System.nanoTime());
                            if (active.incrementAndGet() > 1) overlap.set(true);
                            for (int n = 0; n < EACH; n++) {
                                engine.start(rule, new MapLogicContext(Map.of()).withVariables(binding),
                                        RunOwner.of("origin-" + index, null));
                            }
                            active.decrementAndGet();
                            times.set(index * 2 + 1, System.nanoTime());
                        }, 1);
                    }
                    };
                    for (int origin = 0; origin < 2; origin++) {
                        int chunkX = origin == 0 ? 0 : 1024;
                        Bukkit.getRegionScheduler().execute(addon, world, chunkX, 0, () -> {
                            world.getChunkAt(chunkX, 0).addPluginChunkTicket(addon);
                            if (ready.incrementAndGet() == 2) launch.run();
                        });
                    }
                });
    }

    static void offline(FoliaProbe addon, UUID identity) {
        MaterionePlugin plugin = (MaterionePlugin) Bukkit.getPluginManager().getPlugin("Materione");
        World world = Bukkit.getWorlds().get(0);
        world.getChunkAtAsync(0, 0).thenAccept(chunk ->
                Bukkit.getRegionScheduler().execute(addon, world, 0, 0, () -> {
                    var reads = new CurrencyReads(true, GatewayRunScheduler.ThreadProbe.SERVER);
                    var scheduler = new GatewayRunScheduler(addon, plugin.scheduling());
                    var executor = new RunReadExecutor(scheduler, null);
                    var calls = new AtomicInteger();
                    var backend = new OfflineBackend(calls);
                    boolean denied = false;
                    try {
                        reads.readOffline(backend, () -> backend.balance(identity));
                    } catch (ReadUnavailableException expected) {
                        denied = true;
                    }
                    var unsupported = new OfflineBackend(calls) {
                        @Override public CurrencyReadCapability readCapability() {
                            return new CurrencyReadCapability(Threading.GLOBAL, Availability.AVAILABLE, null);
                        }
                    };
                    boolean rejected = false;
                    try {
                        reads.readOffline(unsupported, () -> unsupported.balance(identity));
                    } catch (ReadUnavailableException expected) {
                        rejected = true;
                    }
                    final boolean syncDenied = denied;
                    final boolean unsupportedDenied = rejected && reads.scheduleOffline(unsupported, executor,
                            () -> unsupported.balance(identity)).toCompletableFuture().isCompletedExceptionally();
                    boolean preconditions = syncDenied && unsupportedDenied && calls.get() == 0
                            && Bukkit.isOwnedByCurrentRegion(world, 0, 0) && !Bukkit.isGlobalTickThread();
                    reads.scheduleOffline(backend, executor,
                            () -> reads.readOffline(backend, () -> backend.balance(identity)))
                            .whenComplete((result, failure) -> {
                                boolean online = Bukkit.getPlayer(identity) != null;
                                boolean ok = preconditions && calls.get() == 1
                                        && (online ? failure != null && failure.getMessage().contains("identity is online")
                                        : failure == null && result instanceof EconomyResult.Succeeded succeeded
                                                && succeeded.balance() == 73);
                                addon.getLogger().info("MTR250-OFFLINE " + (ok ? "PASS" : "FAIL")
                                        + " online=" + online + " synchronousDenied=" + syncDenied
                                        + " unsupportedDenied=" + unsupportedDenied + " calls=" + calls.get()
                                        + " global=" + Bukkit.isGlobalTickThread()
                                        + " result=" + result + " error=" + failure);
                                executor.close();
                            });
                }));
    }

    private static class OfflineBackend implements CurrencyBackend {
        private final AtomicInteger calls;
        OfflineBackend(AtomicInteger calls) { this.calls = calls; }
        @Override public CurrencyReadCapability readCapability() {
            return new CurrencyReadCapability(Threading.GLOBAL, Availability.AVAILABLE, Threading.GLOBAL);
        }
        @Override public EconomyResult balance(UUID identity) {
            calls.incrementAndGet();
            if (!Bukkit.isGlobalTickThread()) return new EconomyResult.Failed("wrong global owner");
            if (Bukkit.getPlayer(identity) != null) return new EconomyResult.Failed("identity is online");
            return new EconomyResult.Succeeded(73);
        }
        @Override public EconomyResult has(UUID identity, double amount) { return balance(identity); }
        @Override public EconomyResult withdraw(UUID identity, double amount) {
            return new EconomyResult.Failed("read-only probe");
        }
        @Override public EconomyResult deposit(UUID identity, double amount) {
            return new EconomyResult.Failed("read-only probe");
        }
    }
}
