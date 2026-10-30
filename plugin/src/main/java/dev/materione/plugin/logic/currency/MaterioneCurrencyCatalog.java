package dev.materione.plugin.logic.currency;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.behavior.logic.LogicIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The live currency catalog (logic.md §18): the built-in {@code materione:money} plus the currencies
 * the last apply defined. It resolves names, and it binds each currency's integration at use, so an
 * integration installed after the currency was defined answers without a reload.
 *
 * <p>The live list is replaced by {@link #define} after every successful swap, from the same runtime
 * view the rest of the content reads. A built-in can never be replaced; a currency in the reserved
 * namespace is dropped here as well as at parse, so the built-in is the only {@code materione:} currency.
 */
public final class MaterioneCurrencyCatalog implements CurrencyCatalog {

    /** The always-present currency: Vault's economy, dollars, two decimals. */
    public static final Currency MONEY = new Currency(
            NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, "money"), "Money", "$", 2, "vault", Map.of());

    private final Function<String, Optional<CurrencyIntegration>> integrations;
    private final String defaultAuthored;
    private volatile List<Currency> defined = List.of();

    /**
     * @param integrations resolves an authored integration name ({@code vault}) to its registered entry
     * @param defaultAuthored the {@code default_currency} setting: the currency used when an action names none
     */
    public MaterioneCurrencyCatalog(Function<String, Optional<CurrencyIntegration>> integrations, String defaultAuthored) {
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.defaultAuthored = defaultAuthored == null || defaultAuthored.isBlank()
                ? LogicIds.BUILT_IN_NAMESPACE + ":money" : defaultAuthored;
    }

    /** Replaces the currencies content defined in the last apply. */
    public void define(List<Currency> currencies) {
        var kept = new ArrayList<Currency>();
        for (Currency currency : currencies) {
            if (!LogicIds.BUILT_IN_NAMESPACE.equals(currency.id().namespace())) {
                kept.add(currency);
            }
        }
        defined = List.copyOf(kept);
    }

    @Override
    public List<Currency> all() {
        var all = new ArrayList<Currency>();
        all.add(MONEY);
        all.addAll(defined);
        return List.copyOf(all);
    }

    @Override
    public Optional<Currency> get(NamespacedId id) {
        return all().stream().filter(currency -> currency.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Currency> resolve(String authored) {
        if (authored == null || authored.isBlank()) {
            return Optional.empty();
        }
        if (authored.contains(":")) {
            return LogicIds.parse(authored).flatMap(this::get);
        }
        // a bare name: the built-in first, otherwise the one currency of that name in any pack
        var matches = all().stream().filter(currency -> currency.id().value().equals(authored)).toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        for (Currency match : matches) {
            if (LogicIds.BUILT_IN_NAMESPACE.equals(match.id().namespace())) {
                return Optional.of(match);
            }
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    @Override
    public Currency defaultCurrency() {
        return resolve(defaultAuthored).orElse(MONEY);
    }

    @Override
    public Optional<CurrencyBackend> backend(Currency currency) {
        Optional<CurrencyBackend> bound = bind(currency);
        return bound.filter(backend -> backend.availability().available());
    }

    @Override
    public Availability availability(Currency currency) {
        var integration = integrations.apply(currency.integration());
        if (integration.isEmpty()) {
            return Availability.unavailable("the " + currency.integration()
                    + " integration is not installed, so " + currency.id() + " cannot be used");
        }
        CurrencyBackend backend;
        try {
            backend = integration.get().bind(ParamReader.of(currency.config()));
        } catch (IllegalArgumentException e) {
            return Availability.unavailable(currency.id() + " cannot be used: " + e.getMessage());
        }
        Availability own = backend.availability();
        return own.available() ? Availability.AVAILABLE : own;
    }

    private Optional<CurrencyBackend> bind(Currency currency) {
        var integration = integrations.apply(currency.integration());
        if (integration.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(integration.get().bind(ParamReader.of(currency.config())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
