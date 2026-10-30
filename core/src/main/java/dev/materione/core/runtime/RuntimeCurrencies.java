package dev.materione.core.runtime;

import dev.materione.api.logic.provider.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The currencies one apply defined (logic.md §17): the pack files that parsed and bound, as the live
 * view the currency catalog reads after each swap. Currencies are content, so they follow the same
 * build, validate and swap path as items and sounds (D6); money itself is never stored here.
 *
 * @param currencies the defined currencies, in discovery order
 */
public record RuntimeCurrencies(List<Currency> currencies) {

    /** The view key the pipeline registers this under and the catalog reads it from. */
    public static final Class<RuntimeCurrencies> KEY = RuntimeCurrencies.class;

    public RuntimeCurrencies {
        currencies = List.copyOf(Objects.requireNonNull(currencies, "currencies"));
    }

    /** No currencies defined (a server with no currency files). */
    public static RuntimeCurrencies none() {
        return new RuntimeCurrencies(List.of());
    }
}
