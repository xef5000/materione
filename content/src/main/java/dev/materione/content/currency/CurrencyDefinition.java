package dev.materione.content.currency;

import dev.materione.api.logic.provider.Currency;

/**
 * One parsed currency file (logic.md §18). A thin wrapper so the pipeline can tell a currency apart
 * from every other parsed definition without a second schema.
 *
 * @param currency the currency as the content defines it
 */
public record CurrencyDefinition(Currency currency) {}
