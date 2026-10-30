package dev.materione.api.logic.provider;

import dev.materione.api.logic.Availability;
import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Optional;

/**
 * The defined currencies and the way to reach their money (logic.md §18). Read at use: an action asks
 * for the currency and its backend each time it runs, so a currency file changed by a reload, or an
 * integration installed later, takes effect without restarting the action set.
 *
 * <p>The built-in {@code materione:money} (bound to Vault) is always in {@link #all()}, with or without
 * a currency file. The default currency is the {@code default_currency} setting, which falls back to
 * {@code materione:money} when it names nothing defined.
 */
public interface CurrencyCatalog {

    /** Every defined currency, the built-in first. */
    List<Currency> all();

    /** The currency with exactly this id. */
    Optional<Currency> get(NamespacedId id);

    /**
     * Resolves an authored name: a qualified id ({@code shop:gems}) by exact match, or a bare name
     * ({@code gems}) that matches {@code materione:} first and otherwise the one currency of that name
     * in any pack. An ambiguous bare name resolves to nothing and must be qualified.
     */
    Optional<Currency> resolve(String authored);

    /** The currency actions use when they name none. */
    Currency defaultCurrency();

    /** The backend answering for {@code currency} now, or empty when {@link #availability} says why not. */
    Optional<CurrencyBackend> backend(Currency currency);

    /** Whether {@code currency} can be used now: its integration is installed, bound, and answering. */
    Availability availability(Currency currency);
}
