package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.ProviderParams;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * {@code has_money: 250} or {@code has_money: {amount: 250, currency: gems}} (logic.md §18, §22).
 *
 * <p>The currency resolves exactly as {@code give_money} and {@code take_money} resolve it: a named one by
 * {@link ProviderParams#resolve}, otherwise the default. A currency that is unknown, or whose integration
 * cannot answer, is <em>false</em>, so a check on money the server cannot count never grants.
 *
 * <p><b>Threading.</b> A condition runs on the thread of the step that evaluates it, on the player's region
 * (logic.md §16, decision 17). The balance is read through the backend, which declares its own thread
 * ({@code vault} GLOBAL, {@code xp} PLAYER_REGION). On Paper every backend is safe here. On Folia a GLOBAL
 * backend is read from the player's region: the same gap {@code balance()} has (logic.md §18, item 25),
 * and it is closed by the same follow-up (a threading hook on predicates).
 */
public final class HasMoneyCondition implements ConditionType<HasMoneyCondition.Params> {

    /** The amount to hold, and the authored currency name (null for the default). */
    public record Params(double amount, String currency) {}

    private final LogicProviders providers;

    /** @param providers the provider slots, read on every test and availability check */
    public HasMoneyCondition(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
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
        CurrencyCatalog catalog = providers.currencies();
        Currency currency = ProviderParams.resolve(catalog, params.currency());
        if (currency == null) {
            return false;
        }
        CurrencyBackend backend = catalog.backend(currency).orElse(null);
        if (backend == null) {
            return false;
        }
        return backend.has(player.getUniqueId(), params.amount()) instanceof EconomyResult.Succeeded;
    }

    /** Available when the default currency can be used, as for {@code take_money}. */
    @Override
    public Availability availability() {
        CurrencyCatalog catalog = providers.currencies();
        Availability own = catalog.availability(catalog.defaultCurrency());
        return own.available() ? Availability.AVAILABLE : own;
    }
}
