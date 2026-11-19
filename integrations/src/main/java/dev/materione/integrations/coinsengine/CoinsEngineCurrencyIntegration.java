package dev.materione.integrations.coinsengine;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * The {@code coinsengine} currency integration (logic.md §18): one of the currencies CoinsEngine defines.
 * CoinsEngine runs any number of currencies, so each Materione currency names the one it wraps with
 * {@code currency:}, and several Materione currencies may wrap several CoinsEngine ones. It registers under
 * {@code materione:coinsengine} only while CoinsEngine is enabled.
 *
 * <p>CoinsEngine's current releases ship as ExcellentEconomy (the API package is
 * {@code su.nightexpress.excellenteconomy}), so both plugin names are accepted. Its API is compiled for a
 * newer Java than Materione's floor, so it is reached by reflection ({@link CoinsEngineApi}) and Materione
 * has no compile dependency on it.
 */
public final class CoinsEngineCurrencyIntegration implements CurrencyIntegration {

    /** The plugin names a server may run this under: the old name and the current one. */
    public static final List<String> PLUGINS = List.of("CoinsEngine", "ExcellentEconomy");

    @Override
    public String id() {
        return "coinsengine";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("CoinsEngine",
                "One of the currencies CoinsEngine defines. Name it with currency; a player must be online to use it.",
                List.of(new ParameterSchema("currency", ParameterType.STRING, true, null,
                        "The CoinsEngine currency id, as in its currencies folder (coins, gems)", null, null)),
                "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        String currency = config.text("currency", "").trim();
        if (currency.isEmpty()) {
            throw new IllegalArgumentException("currency must name a CoinsEngine currency, such as coins");
        }
        return new CoinsEngineCurrencyBackend(currency);
    }
}
