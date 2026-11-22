package dev.materione.plugin.logic.action;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
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
import dev.materione.plugin.logic.currency.CurrencyReads;
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * {@code take_money: 250} or {@code take_money: {amount: 250, currency: gems}}: withdraws money from
 * the run's player (logic.md §18). A balance that cannot cover the amount fails the step with
 * {@code insufficient funds} and takes nothing, so a flow can branch on the refusal with {@code require}
 * (logic.md §2.2). An amount that is not a number above 0 fails it the same way, with nothing taken (logic.md §42).
 * Currency resolution and the per-currency thread are as for {@code give_money}.
 */
public final class TakeMoneyAction implements ActionType<TakeMoneyAction.Params> {

    /** The parsed step: the amount (a literal or a template) and the authored currency, or null for the default. */
    public record Params(Quantity amount, String currency) {}

    private final LogicProviders providers;
    private final CurrencyReads reads;

    /** @param providers the provider slots, read on every run and availability check */
    public TakeMoneyAction(LogicProviders providers) {
        this(providers, CurrencyReads.paper());
    }

    public TakeMoneyAction(LogicProviders providers, CurrencyReads reads) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.reads = Objects.requireNonNull(reads, "reads");
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
                                "Amount to take; above 0, or a template that renders to one", null, null).acceptingExpression(),
                        new ParameterSchema("currency", ParameterType.STRING, false, null,
                                "Currency id, e.g. gems or shop:gems; the default currency when omitted", null, null).withWidget("reference", "currency")),
                "2.0.0"), LogicCategory.ECONOMY, "amount").withTargets(TargetKind.PLAYER);
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    /** The thread of the currency this step names (logic.md §18); as for {@code give_money}. */
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
        Player player = Subjects.player(context).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        OptionalDouble amount = ProviderParams.runAmount(params.amount(), context);
        if (amount.isEmpty()) {
            return ActionOutcome.refused(DiagnosticCodes.MONEY_AMOUNT_REFUSED,
                    ProviderParams.refusedAmount(params.amount(), context));
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
        return switch (backend.withdraw(player.getUniqueId(), amount.getAsDouble())) {
            case EconomyResult.Succeeded ignored -> ActionOutcome.OK;
            case EconomyResult.InsufficientFunds funds -> ActionOutcome.failed(
                    "insufficient funds: balance " + funds.balance() + ", needs " + amount.getAsDouble());
            case EconomyResult.Failed failed -> ActionOutcome.failed(failed.reason());
        };
    }

    /** A template amount is rendered first, then the withdrawal runs on the currency's thread; as for {@code give_money}. */
    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        return params.amount().resolveResumable(context, reads).thenCompose(amount ->
                reads.on(threading(params), () -> execute(new Params(amount, params.currency()), context)));
    }

    /** Available when the default currency can be used; the reason names what is missing otherwise. */
    @Override
    public Availability availability() {
        CurrencyCatalog catalog = providers.currencies();
        Availability own = reads.availabilityResolved(catalog, catalog.defaultCurrency());
        return own.available() ? Availability.AVAILABLE : own;
    }
}
