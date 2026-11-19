package dev.materione.integrations.rediseconomy;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.unnm3d.rediseconomy.api.RedisEconomyAPI;
import java.util.List;

/**
 * The {@code rediseconomy} currency integration (logic.md §18): one of the currencies RedisEconomy keeps in
 * Redis, shared by every server on the same Redis. RedisEconomy runs several currencies, so
 * {@code currency:} names one; leaving it out means RedisEconomy's default currency. It registers under
 * {@code materione:rediseconomy} only while RedisEconomy is enabled.
 */
public final class RedisEconomyCurrencyIntegration implements CurrencyIntegration {

    /** The plugin name as RedisEconomy's {@code plugin.yml} declares it. */
    public static final String PLUGIN = "RedisEconomy";

    public RedisEconomyCurrencyIntegration() {
        RedisEconomyAPI.class.getName();
    }

    @Override
    public String id() {
        return "rediseconomy";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("RedisEconomy",
                "One of the currencies RedisEconomy shares through Redis; its default currency when currency is left out.",
                List.of(new ParameterSchema("currency", ParameterType.STRING, false, null,
                        "The RedisEconomy currency name; RedisEconomy's default currency when omitted", null, null)),
                "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new RedisEconomyCurrencyBackend(config.optionalText("currency").map(String::trim)
                .filter(name -> !name.isEmpty()).orElse(null));
    }
}
