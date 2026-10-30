package dev.materione.api.logic.provider;

import dev.materione.api.NamespacedId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A named currency as the content defines it (logic.md §16): how it is called, written, and which
 * integration answers for it. The money itself lives behind {@link CurrencyBackend}, bound from
 * {@link #config} when used.
 *
 * @param id the pack-namespaced id ({@code shop:gems}); the built-in {@code materione:money} is always present
 * @param displayName the name shown to players ({@code Gems})
 * @param symbol the prefix formatting puts before the amount ({@code G$}); may be blank
 * @param decimals digits after the point when formatting, 0 to 6
 * @param integration the integration's id ({@code custom})
 * @param config the currency's own mapping, as authored, passed to {@link CurrencyIntegration#bind}
 */
public record Currency(
        NamespacedId id,
        String displayName,
        String symbol,
        int decimals,
        String integration,
        Map<String, Object> config) {

    /** The largest number of decimals a currency may format with. */
    public static final int MAX_DECIMALS = 6;

    public Currency {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(integration, "integration");
        if (integration.isBlank()) {
            throw new IllegalArgumentException("a currency needs an integration");
        }
        if (decimals < 0 || decimals > MAX_DECIMALS) {
            throw new IllegalArgumentException("decimals must be 0.." + MAX_DECIMALS);
        }
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
