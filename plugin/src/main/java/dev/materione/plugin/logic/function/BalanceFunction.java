package dev.materione.plugin.logic.function;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * {@code balance(currency)}: the run's player's balance of a named currency, or null when there is no
 * player, the currency is not defined, or its integration cannot answer now (logic.md §17).
 *
 * <p><b>Not pure.</b> It reads the player's money through the currency's backend, so it is a read of
 * live state, like {@code random} is a draw: the same call can answer differently later. It is the
 * function a condition or a message uses, and it is {@code formatted} with {@code format_currency}.
 */
public final class BalanceFunction implements ExpressionFunction {

    private final LogicProviders providers;

    /** @param providers the currency catalog the name resolves against */
    public BalanceFunction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "balance";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Balance",
                "The player's balance of a currency, read now; null without a player or when the currency cannot answer. "
                        + "It reads live state, so the same call can give a different answer later.",
                List.of(FunctionArgs.param("currency", ParameterType.STRING, true,
                        "Currency id, e.g. money or shop:gems")),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        if (!(arguments.get(0) instanceof String name)) {
            return null;
        }
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return null;
        }
        CurrencyCatalog catalog = providers.currencies();
        Currency currency = catalog.resolve(name).orElse(null);
        if (currency == null) {
            return null;
        }
        CurrencyBackend backend = catalog.backend(currency).orElse(null);
        if (backend == null) {
            return null;
        }
        return backend.balance(player.getUniqueId()) instanceof EconomyResult.Succeeded ok ? ok.balance() : null;
    }
}
