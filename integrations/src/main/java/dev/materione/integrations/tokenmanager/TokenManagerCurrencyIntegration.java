package dev.materione.integrations.tokenmanager;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import me.realized.tokenmanager.api.TokenManager;

/**
 * The {@code tokenmanager} currency integration (logic.md §18): TokenManager's tokens. TokenManager has
 * one currency, so it takes no keys. It registers under {@code materione:tokenmanager} only while
 * TokenManager is enabled.
 */
public final class TokenManagerCurrencyIntegration implements CurrencyIntegration {

    /** The plugin name as TokenManager's {@code plugin.yml} declares it. */
    public static final String PLUGIN = "TokenManager";

    public TokenManagerCurrencyIntegration() {
        TokenManager.class.getName();
    }

    @Override
    public String id() {
        return "tokenmanager";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("TokenManager",
                "The tokens TokenManager keeps for each player. Whole numbers only; a player must be online.",
                List.<ParameterSchema>of(), "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new TokenManagerCurrencyBackend();
    }
}
