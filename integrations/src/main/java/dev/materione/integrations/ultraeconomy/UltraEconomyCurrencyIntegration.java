package dev.materione.integrations.ultraeconomy;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import me.TechsCode.UltraEconomy.UltraEconomyAPI;

/**
 * The {@code ultraeconomy} currency integration (logic.md §18): one of the currencies UltraEconomy defines,
 * named with {@code currency:}. UltraEconomy keeps each currency in two places on an account, the hand and
 * the bank, so {@code balance:} picks which one this Materione currency is (the bank by default). It
 * registers under {@code materione:ultraeconomy} only while UltraEconomy is enabled.
 */
public final class UltraEconomyCurrencyIntegration implements CurrencyIntegration {

    /** The plugin name as UltraEconomy's {@code plugin.yml} declares it. */
    public static final String PLUGIN = "UltraEconomy";

    static final List<String> PURSES = List.of("bank", "hand");

    public UltraEconomyCurrencyIntegration() {
        UltraEconomyAPI.class.getName();
    }

    @Override
    public String id() {
        return "ultraeconomy";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("UltraEconomy",
                "One of the currencies UltraEconomy defines, read from the player's bank or from their hand.",
                List.of(
                        new ParameterSchema("currency", ParameterType.STRING, true, null,
                                "The UltraEconomy currency name", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Currency", "economy", 10, false, "text", "", "Dollars")),
                        new ParameterSchema("balance", ParameterType.ENUM, false, "bank",
                                "Which part of the account is the currency: bank or hand", PURSES, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Balance", "economy", 20, false, "text", "", "bank"))),
                "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        String currency = config.text("currency", "").trim();
        if (currency.isEmpty()) {
            throw new IllegalArgumentException("currency must name an UltraEconomy currency");
        }
        return new UltraEconomyCurrencyBackend(currency, "hand".equals(config.choice("balance", "bank", PURSES)));
    }
}
