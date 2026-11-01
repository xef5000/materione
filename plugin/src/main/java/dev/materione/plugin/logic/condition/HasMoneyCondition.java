package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.ProviderParams;
import dev.materione.plugin.logic.currency.CurrencyReads;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * {@code has_money: 250} or {@code has_money: {amount: 250, currency: gems}} (logic.md §18, §22).
 *
 * <p>The currency resolves exactly as {@code give_money} and {@code take_money} resolve it: a named one by
 * {@link ProviderParams#resolve}, otherwise the default. A currency that is unknown, or whose integration
 * cannot answer, is <em>false</em>, so a check on money the server cannot count never grants.
 *
 * <p>The shared read guard checks the backend's explicit Folia capability and the current owner;
 * an unsafe synchronous read is unavailable rather than a false or guessed balance.
 */
public final class HasMoneyCondition implements ConditionType<HasMoneyCondition.Params> {

    /** The amount to hold, and the authored currency name (null for the default). */
    public record Params(double amount, String currency) {}

    private final LogicProviders providers;
    private final CurrencyReads reads;

    /** @param providers the provider slots, read on every test and availability check */
    public HasMoneyCondition(LogicProviders providers) {
        this(providers, CurrencyReads.paper());
    }

    public HasMoneyCondition(LogicProviders providers, CurrencyReads reads) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override
    public String id() {
        return "has_money";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Has money",
                "True when the player's balance of a currency is at least the amount",
                List.of(
                        new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                                "Amount the balance must reach; above 0", null, null),
                        new ParameterSchema("currency", ParameterType.STRING, false, null,
                                "Currency id, e.g. gems or shop:gems; the default currency when omitted", null, null)),
                "2.0.0"), LogicCategory.ECONOMY, "amount");
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(ProviderParams.amount(params), ProviderParams.currency(params));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }

        CurrencyCatalog catalog = reads.lookup(providers::currencies);
        Currency currency = reads.lookup(() -> ProviderParams.resolve(catalog, params.currency()));
        if (currency == null) {
            reads.unavailable("currency is unavailable");
            return false;
        }
        return reads.readResolved(catalog, currency, player,
                backend -> backend.has(player.getUniqueId(), params.amount()))
                instanceof EconomyResult.Succeeded;
    }

    @Override
    public CompletionStage<Boolean> testResumable(Params params, LogicContext context, ReadExecutor executor) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) return CompletableFuture.completedFuture(false);
        return executor.on(dev.materione.api.logic.Threading.ANY, () -> {
            CurrencyCatalog catalog = reads.lookup(providers::currencies);
            Currency currency = reads.lookup(() -> ProviderParams.resolve(catalog, params.currency()));
            if (currency == null) {
                reads.unavailable("currency is unavailable");
                return null;
            }
            return new Selection(catalog, currency);
        }).thenCompose(selection -> selection == null ? CompletableFuture.completedFuture(false)
                : reads.scheduleResolved(selection.catalog(), selection.currency(), player, executor,
                        backend -> backend.has(player.getUniqueId(), params.amount()))
                        .thenApply(result -> result instanceof EconomyResult.Succeeded));
    }

    private record Selection(CurrencyCatalog catalog, Currency currency) {}

    /** Available when the default currency can be used, as for {@code take_money}. */
    @Override
    public Availability availability() {
        CurrencyCatalog catalog = providers.currencies();
        Availability own = reads.availabilityResolved(catalog, catalog.defaultCurrency());
        return own.available() ? Availability.AVAILABLE : own;
    }
}
