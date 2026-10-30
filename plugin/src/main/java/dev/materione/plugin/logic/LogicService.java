package dev.materione.plugin.logic;

import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.behavior.logic.RegistryLogicLookup;
import dev.materione.behavior.logic.VocabularyBuilder;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
import dev.materione.core.registry.RegistryService;
import java.util.List;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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
    private final LogicRegistryImpl<dev.materione.api.logic.provider.CurrencyIntegration> currencyIntegrations;
    private final MaterioneCurrencyCatalog currencies;
    private final LogicProvidersImpl providers;
    private boolean closed;

    /**
     * @param host the plugin that hosts the API; its plugin manager delivers owner-disable events
     * @param registries the service that owns the logic registries
     */
    public LogicService(Plugin host, RegistryService registries) {
        this(host, registries, null);
    }

    /**
     * @param defaultCurrency the {@code default_currency} setting; blank or null means {@code materione:money}
     */
    public LogicService(Plugin host, RegistryService registries, String defaultCurrency) {
        this.registries = Objects.requireNonNull(registries, "registries");
        this.currencyIntegrations = new LogicRegistryImpl<>(registries, RegistryKeys.CURRENCY_INTEGRATIONS,
                dev.materione.api.logic.provider.CurrencyIntegration.class, "currency integration",
                dev.materione.api.logic.provider.CurrencyIntegration::id,
                integration -> integration.describe());
        this.currencies = new MaterioneCurrencyCatalog(currencyIntegrations::resolve, defaultCurrency);
        this.actions = new LogicRegistryImpl<>(registries, RegistryKeys.ACTIONS, erased(ActionType.class),
                "action", ActionType::id, type -> type.describe().self());
        this.conditions = new LogicRegistryImpl<>(registries, RegistryKeys.CONDITIONS, erased(ConditionType.class),
                "condition", ConditionType::id, type -> type.describe().self());
        this.triggers = new LogicRegistryImpl<>(registries, RegistryKeys.TRIGGERS, TriggerType.class,
                "trigger", TriggerType::id, type -> type.describe().self());
        this.functions = new LogicRegistryImpl<>(registries, RegistryKeys.EXPRESSION_FUNCTIONS,
                ExpressionFunction.class, "function", ExpressionFunction::id, type -> type.describe().self());
        Objects.requireNonNull(host, "host").getServer().getPluginManager().registerEvents(this, host);
        // the session-only Bukkit permission provider is the default (logic.md §4.5)
        this.providers = new LogicProvidersImpl(new BukkitPermissions(host), currencies);
    }

    /** The {@code currency-integrations} registry (logic.md §16). */
    @Override
    public LogicRegistry<dev.materione.api.logic.provider.CurrencyIntegration> currencyIntegrations() {
        return currencyIntegrations;
    }

    /**
     * Makes the currencies of the live view the catalog's current list (logic.md §16). Called after
     * every successful swap, and with an empty view when nothing is live, so a removed currency leaves.
     */
    public void updateCurrencies(dev.materione.core.runtime.RuntimeRegistries live) {
        var view = live == null ? null : live.view(dev.materione.core.runtime.RuntimeCurrencies.KEY);
        currencies.define(view == null ? List.of() : view.currencies());
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
    public LogicProviders providers() {
        return providers;
    }

    @Override
    public BehaviorVocabulary vocabulary() {
        return VocabularyBuilder.build(registries);
    }

    /** What the rule parser resolves actions and triggers through: the live registries. */
    public RegistryLogicLookup lookup() {
        return new RegistryLogicLookup(registries);
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
        currencyIntegrations.ownerDisabled(owner);
        providers.ownerDisabled(owner);
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
        currencyIntegrations.close();
        HandlerList.unregisterAll(this);
    }
}
