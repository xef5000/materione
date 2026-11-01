package dev.materione.plugin.logic.function;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.currency.CurrencyReads;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * {@code balance(currency)}: the run's player's balance of a named currency, or null when there is no
 * player, the currency is not defined, or its integration cannot answer now (logic.md §18).
 *
 * <p><b>Not pure.</b> It reads the player's money through the currency's backend, so it is a read of
 * live state, like {@code random} is a draw: the same call can answer differently later. It is the
 * function a condition or a message uses, and it is {@code formatted} with {@code format_currency}.
 */
public final class BalanceFunction implements ExpressionFunction {

    private final LogicProviders providers;
    private final CurrencyReads reads;

    /** @param providers the currency catalog the name resolves against */
    public BalanceFunction(LogicProviders providers) {
        this(providers, CurrencyReads.paper());
    }

    public BalanceFunction(LogicProviders providers, CurrencyReads reads) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.reads = Objects.requireNonNull(reads, "reads");
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
        CurrencyCatalog catalog = reads.lookup(providers::currencies);
        Currency currency = reads.lookup(() -> catalog.resolve(name).orElse(null));
        if (currency == null) {
            reads.unavailable("currency '" + name + "' is unavailable");
            return null;
        }
        return reads.readResolved(catalog, currency, player, backend -> backend.balance(player.getUniqueId()))
                instanceof EconomyResult.Succeeded ok ? ok.balance() : null;
    }

    @Override
    public CompletionStage<Object> callResumable(
            List<Object> arguments, LogicContext context, ReadExecutor executor) {
        if (!(arguments.get(0) instanceof String name)) {
            return CompletableFuture.completedFuture(null);
        }
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) return CompletableFuture.completedFuture(null);
        return executor.on(dev.materione.api.logic.Threading.ANY, () -> {
            CurrencyCatalog catalog = reads.lookup(providers::currencies);
            Currency currency = reads.lookup(() -> catalog.resolve(name).orElse(null));
            if (currency == null) {
                reads.unavailable("currency '" + name + "' is unavailable");
                return null;
            }
            return new Selection(catalog, currency);
        }).thenCompose(selection -> selection == null ? CompletableFuture.completedFuture(null)
                : reads.scheduleResolved(selection.catalog(), selection.currency(), player, executor,
                        backend -> backend.balance(player.getUniqueId()))
                        .thenApply(result -> result instanceof EconomyResult.Succeeded ok ? ok.balance() : null));
    }

    private record Selection(CurrencyCatalog catalog, Currency currency) {}
}
