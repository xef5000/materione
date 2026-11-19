package dev.materione.integrations.excellenteconomy;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;

/**
 * The {@code excellenteconomy} currency integration (logic.md §18): one of the currencies ExcellentEconomy
 * defines. ExcellentEconomy runs any number of currencies, so each
 * Materione currency names the one it wraps with {@code currency:}, and several Materione currencies may
 * wrap several ExcellentEconomy ones. It registers under {@code materione:excellenteconomy} only while
 * ExcellentEconomy is enabled.
 *
 * <p>This class lives in its own module because ExcellentEconomy's API is compiled for Java 25, above
 * Materione's Java 21 floor (see this module's build file). It is only ever loaded on a server where
 * ExcellentEconomy runs, which is a Java 25 server.
 */
public final class ExcellentEconomyCurrencyIntegration implements CurrencyIntegration {

    /** The plugin name as ExcellentEconomy's {@code plugin.yml} declares it. */
    public static final String PLUGIN = "ExcellentEconomy";

    public ExcellentEconomyCurrencyIntegration() {
        // loads ExcellentEconomy's type here, so an incompatible plugin fails inside the integration guard
        ExcellentEconomyAPI.class.getName();
    }

    @Override
    public String id() {
        return "excellenteconomy";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("ExcellentEconomy",
                "One of the currencies ExcellentEconomy defines. Name it with currency; a player must be online to use it.",
                List.of(new ParameterSchema("currency", ParameterType.STRING, true, null,
                        "The ExcellentEconomy currency id, as in its currencies folder (coins, gems)", null, null)),
                "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        String currency = config.text("currency", "").trim();
        if (currency.isEmpty()) {
            throw new IllegalArgumentException("currency must name an ExcellentEconomy currency, such as coins");
        }
        return new ExcellentEconomyCurrencyBackend(currency);
    }
}
