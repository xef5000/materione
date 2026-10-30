package dev.materione.api.logic.provider;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.SelfDescription;

/**
 * How a named currency reaches its money (logic.md §4.5, §18): Vault's economy, the player's
 * experience, a command pair with a PlaceholderAPI balance, or a provider an addon registers. One
 * entry per kind, in the {@code currency-integrations} registry; a currency file names one by id and
 * carries that integration's own keys.
 *
 * <p>An integration is a factory. {@link #bind} runs once when a currency is defined or used: it is
 * pure over its configuration, it rejects bad keys with {@link IllegalArgumentException} (the currency
 * is then quarantined with {@code MTR-0062}), and it returns the backend that answers for that
 * currency. Binding is repeated at use, so an integration whose plugin arrived later is picked up
 * without a reload.
 *
 * <p>{@link #describe()} is the self-description of the keys {@link #bind} reads. The studio renders
 * its form from it, as it does for every other type (D5).
 */
public interface CurrencyIntegration {

    /** The local name a currency file writes as {@code integration:} ({@code vault}, {@code xp}, {@code custom}). */
    String id();

    /** The keys this integration reads from a currency's own mapping. */
    SelfDescription describe();

    /**
     * Turns a currency's integration keys into a backend.
     *
     * @param config the currency's mapping, read with strict typed accessors
     * @throws IllegalArgumentException to reject the currency, with a message an author can act on
     */
    CurrencyBackend bind(ParamReader config);
}
