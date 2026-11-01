package dev.materione.plugin.logic.variable;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.integrations.variables.CurrencyPlaceholderBridge;
import dev.materione.plugin.logic.GatewayRunScheduler.ThreadProbe;
import dev.materione.plugin.logic.currency.CurrencyReads;
import dev.materione.plugin.logic.function.FormatCurrencyFunction;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.entity.Player;

/** Synchronous, owner-proven access to the live catalog for the PlaceholderAPI expansion. */
public final class CurrencyPlaceholderReads implements CurrencyPlaceholderBridge {
    private final LogicProviders providers;
    private final LogicRegistry<CurrencyIntegration> integrations;
    private final CurrencyReads reads;
    private final ThreadProbe probe;
    private final Function<UUID, Player> online;
    private final boolean folia;
    private final Set<Request> active = ConcurrentHashMap.newKeySet();

    private record Request(NamespacedId id, UUID viewer) { }

    public CurrencyPlaceholderReads(LogicProviders providers, LogicRegistry<CurrencyIntegration> integrations,
            CurrencyReads reads, ThreadProbe probe, Function<UUID, Player> online, boolean folia) {
        this.providers = Objects.requireNonNull(providers);
        this.integrations = Objects.requireNonNull(integrations);
        this.reads = Objects.requireNonNull(reads);
        this.probe = Objects.requireNonNull(probe);
        this.online = Objects.requireNonNull(online);
        this.folia = folia;
    }

    @Override
    public Collection<NamespacedId> currencies() {
        return reads.lookup(() -> providers.currencies().all().stream().map(Currency::id).toList());
    }

    @Override
    public Balance read(NamespacedId id, UUID viewer, boolean formatted) {
        Request request = new Request(id, viewer);
        if (!active.add(request)) {
            return failed("currency placeholder read is already in progress");
        }
        try {
            return readOnce(id, viewer, formatted);
        } finally {
            active.remove(request);
        }
    }

    private Balance readOnce(NamespacedId id, UUID viewer, boolean formatted) {
        if (!folia && !probe.onGlobalThread()) {
            return failed("currency placeholder requires the Paper main thread");
        }
        Player player = online.apply(viewer);
        if (!folia && player != null && !probe.ownsEntity(player)) {
            return failed("currency placeholder requires the player's owner");
        }
        CurrencyCatalog catalog = reads.lookup(providers::currencies);
        Currency currency = reads.lookup(() -> catalog.get(id).orElse(null));
        if (currency == null || !uniqueToken(catalog, id, formatted)) {
            return failed("currency was removed or its placeholder became ambiguous");
        }
        RegistryEntry<CurrencyIntegration> registration = registration(currency);
        if (registration == null || registration.generation() == 0) {
            return failed("currency integration registration is unavailable");
        }
        CurrencyIntegration integration = registration.entry();
        var lookupCapability = integration.lookupCapability();
        reads.requirePlaceholderLookup(lookupCapability, player);
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed before lookup");
        }
        CurrencyBackend backend = reads.lookup(() -> catalog.bindForRead(currency, integration).orElse(null));
        if (backend == null) {
            return failed("currency backend is unavailable");
        }
        reads.requirePlaceholderRead(backend, player);
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed before availability");
        }
        var available = reads.lookup(backend::availability);
        if (available == null || !available.available()) {
            return failed(available == null ? "currency availability is missing" : available.reason());
        }
        reads.requirePlaceholderLookup(lookupCapability, player);
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed during availability lookup");
        }
        Object binding = reads.lookup(backend::readBindingIdentity);
        if (binding == null) {
            return failed("currency provider identity is unavailable");
        }
        reads.requirePlaceholderLookup(lookupCapability, player);
        reads.requirePlaceholderRead(backend, player);
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed before the balance read");
        }
        EconomyResult result = player == null
                ? reads.readOffline(backend, () -> backend.balance(viewer))
                : reads.read(backend, player, () -> backend.balanceForPlayer(player, viewer));
        reads.requirePlaceholderLookup(lookupCapability, player);
        reads.requirePlaceholderRead(backend, player);
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed during the balance read");
        }
        available = reads.lookup(backend::availability);
        if (available == null || !available.available()) {
            return failed(available == null ? "currency availability is missing" : available.reason());
        }
        reads.requirePlaceholderLookup(lookupCapability, player);
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed during availability lookup");
        }
        if (reads.lookup(backend::readBindingIdentity) != binding) {
            return failed("currency provider changed during the balance read");
        }
        if (!current(catalog, currency, registration, viewer, player, formatted)) {
            return failed("currency binding or player changed during the balance read");
        }
        String text = formatted && result instanceof EconomyResult.Succeeded succeeded
                ? FormatCurrencyFunction.format(succeeded.balance(), currency) : null;
        return new Balance(result, text);
    }

    private RegistryEntry<CurrencyIntegration> registration(Currency currency) {
        NamespacedId integrationId = LogicIds.parse(currency.integration()).orElse(null);
        return integrationId == null ? null : integrations.entries().stream()
                .filter(entry -> entry.id().equals(integrationId)).findFirst().orElse(null);
    }

    private boolean current(CurrencyCatalog catalog, Currency currency,
            RegistryEntry<CurrencyIntegration> registration, UUID viewer, Player player, boolean formatted) {
        RegistryEntry<CurrencyIntegration> now = registration(currency);
        return online.apply(viewer) == player
                && reads.lookup(() -> catalog.get(currency.id()).orElse(null)) == currency
                && uniqueToken(catalog, currency.id(), formatted)
                && now != null && now.generation() == registration.generation()
                && now.entry() == registration.entry();
    }

    private boolean uniqueToken(CurrencyCatalog catalog, NamespacedId id, boolean formatted) {
        String token = encoded(id) + (formatted ? "_formatted" : "");
        return reads.lookup(() -> catalog.all().stream().map(Currency::id)
                .filter(other -> token.equals(encoded(other)) || token.equals(encoded(other) + "_formatted"))
                .count()) == 1;
    }

    private static String encoded(NamespacedId id) {
        return id.namespace() + "_" + id.value();
    }

    private static Balance failed(String reason) {
        return new Balance(new EconomyResult.Failed(reason), null);
    }
}
