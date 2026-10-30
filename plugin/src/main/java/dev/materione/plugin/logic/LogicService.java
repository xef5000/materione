package dev.materione.plugin.logic;

import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.behavior.logic.RegistryLogicLookup;
import dev.materione.behavior.logic.VocabularyBuilder;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.runtime.CooldownStore;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.registry.RegistryService;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * The {@link LogicApi}: the four logic registries (actions, conditions, triggers, expression
 * functions) as addon-registrable facades over the {@code RegistryService}, plus the vocabulary
 * built from them (logic.md §4, §8). Wires registries to the engine; it holds no policy of its own.
 *
 * <p>Built-ins register through this service exactly like an addon does
 * ({@link BuiltinLogic#register}); the engine resolves rule steps through {@link #lookup()}. A
 * registration is owned by its plugin and is removed when its handle closes, when its owner
 * disables, or when this service closes.
 */
public final class LogicService implements LogicApi, AutoCloseable, Listener {

    private final RegistryService registries;
    private final LogicRegistryImpl<ActionType<?>> actions;
    private final LogicRegistryImpl<ConditionType<?>> conditions;
    private final LogicRegistryImpl<TriggerType> triggers;
    private final LogicRegistryImpl<ExpressionFunction> functions;
    private final RuleEngine engine;
    private boolean closed;

    /**
     * A service for the registries alone, with no way to run flows ({@link #engine()} throws). The
     * vocabulary and registration tests use it; the plugin uses the full constructor.
     */
    public LogicService(Plugin host, RegistryService registries) {
        this(host, registries, null, null);
    }

    /**
     * @param host the plugin that hosts the API; its plugin manager delivers owner-disable and quit events
     * @param registries the service that owns the logic registries
     * @param scheduling the thread gateway parked runs resume through
     * @param diagnostics where run-time diagnostics go
     */
    public LogicService(
            Plugin host, RegistryService registries, SchedulingGateway scheduling, Consumer<Diagnostic> diagnostics) {
        this.registries = Objects.requireNonNull(registries, "registries");
        this.engine = scheduling == null || diagnostics == null
                ? null
                : new RuleEngine(
                        new FlowInterpreter(new GatewayRunScheduler(host, scheduling), RunBudget.DEFAULT, diagnostics),
                        new CooldownStore());
        this.actions = new LogicRegistryImpl<>(registries, RegistryKeys.ACTIONS, erased(ActionType.class),
                "action", ActionType::id, type -> type.describe().self());
        this.conditions = new LogicRegistryImpl<>(registries, RegistryKeys.CONDITIONS, erased(ConditionType.class),
                "condition", ConditionType::id, type -> type.describe().self());
        this.triggers = new LogicRegistryImpl<>(registries, RegistryKeys.TRIGGERS, TriggerType.class,
                "trigger", TriggerType::id, type -> type.describe().self());
        this.functions = new LogicRegistryImpl<>(registries, RegistryKeys.EXPRESSION_FUNCTIONS,
                ExpressionFunction.class, "function", ExpressionFunction::id, type -> type.describe().self());
        Objects.requireNonNull(host, "host").getServer().getPluginManager().registerEvents(this, host);
    }

    /** The generic registry entry types are wildcards ({@code ActionType<?>}); the registry stores the erasure. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Class<T> erased(Class raw) {
        return (Class<T>) raw;
    }

    @Override
    public LogicRegistry<ActionType<?>> actions() {
        return actions;
    }

    @Override
    public LogicRegistry<ConditionType<?>> conditions() {
        return conditions;
    }

    @Override
    public LogicRegistry<TriggerType> triggers() {
        return triggers;
    }

    @Override
    public LogicRegistry<ExpressionFunction> functions() {
        return functions;
    }

    @Override
    public BehaviorVocabulary vocabulary() {
        return VocabularyBuilder.build(registries);
    }

    /** What the rule parser resolves actions and triggers through: the live registries. */
    public RegistryLogicLookup lookup() {
        return new RegistryLogicLookup(registries);
    }

    /**
     * The rule engine every item, block and furniture event goes through (logic.md §5): rule gating,
     * then the resumable interpreter over the thread gateway. The GUI runtime (G1) starts its own
     * runs through it, with a cancellation key, so a closed window can end what it started.
     */
    public RuleEngine engine() {
        if (engine == null) {
            throw new IllegalStateException("this LogicService was built without a scheduling gateway");
        }
        return engine;
    }

    /** A player left: their parked runs end (the entity scheduler would drop them anyway). */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (engine != null) {
            engine.interpreter().cancelPlayer(event.getPlayer().getUniqueId().toString());
        }
    }

    /** Owner disabled: every type it registered leaves its registry. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        ownerDisabled(event.getPlugin());
    }

    /** The owner-disable rule, on the plugin itself (the event's only use). */
    synchronized void ownerDisabled(Plugin owner) {
        actions.ownerDisabled(owner);
        conditions.ownerDisabled(owner);
        triggers.ownerDisabled(owner);
        functions.ownerDisabled(owner);
    }

    /** Removes every registration and stops listening. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        actions.close();
        conditions.close();
        triggers.close();
        functions.close();
        HandlerList.unregisterAll(this);
    }
}
