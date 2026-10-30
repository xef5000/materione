package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
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
 * {@code take_money: 250} or {@code take_money: {amount: 250, currency: gems}}: withdraws money from
 * the run's player (logic.md §17). A balance that cannot cover the amount fails the step with
 * {@code insufficient funds} and takes nothing, so a flow can branch on the refusal with {@code require}
 * (logic.md §2.2). Currency resolution and the per-currency thread are as for {@code give_money}.
 */
public final class TakeMoneyAction implements ActionType<TakeMoneyAction.Params> {

    /** The parsed step; {@code currency} is the authored name, or null for the default. */
    public record Params(double amount, String currency) {}

    private final LogicProviders providers;

    /** @param providers the provider slots, read on every run and availability check */
    public TakeMoneyAction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "take_money";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Take money",
                "Removes money from the player's balance of a currency; fails when the balance is too low",
                List.of(
                        new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                                "Amount to take; above 0", null, null),
                        new ParameterSchema("currency", ParameterType.STRING, false, null,
                                "Currency id, e.g. gems or shop:gems; the default currency when omitted", null, null)),
                "2.0.0"), LogicCategory.ECONOMY, "amount");
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    /** The thread of the currency this step names (logic.md §17); as for {@code give_money}. */
    @Override
    public Threading threading(Params params) {
        CurrencyCatalog catalog = providers.currencies();
        Currency currency = ProviderParams.resolve(catalog, params.currency());
        if (currency == null) {
            return Threading.PLAYER_REGION;
        }
        return catalog.backend(currency).map(CurrencyBackend::threading).orElse(Threading.PLAYER_REGION);
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(ProviderParams.amount(params), ProviderParams.currency(params));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        CurrencyCatalog catalog = providers.currencies();
        Currency currency = ProviderParams.resolve(catalog, params.currency());
        if (currency == null) {
            return ActionOutcome.failed("unknown currency " + params.currency());
        }
        CurrencyBackend backend = catalog.backend(currency).orElse(null);
        if (backend == null) {
            return ActionOutcome.failed(catalog.availability(currency).reason());
        }
        return switch (backend.withdraw(player.getUniqueId(), params.amount())) {
            case EconomyResult.Succeeded ignored -> ActionOutcome.OK;
            case EconomyResult.InsufficientFunds funds -> ActionOutcome.failed(
                    "insufficient funds: balance " + funds.balance() + ", needs " + params.amount());
            case EconomyResult.Failed failed -> ActionOutcome.failed(failed.reason());
        };
    }

    /** Available when the default currency can be used; the reason names what is missing otherwise. */
    @Override
    public Availability availability() {
        CurrencyCatalog catalog = providers.currencies();
        Availability own = catalog.availability(catalog.defaultCurrency());
        return own.available() ? Availability.AVAILABLE : own;
    }
}
