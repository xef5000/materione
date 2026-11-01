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
import dev.materione.plugin.logic.currency.CurrencyReads;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * {@code give_money: 100} or {@code give_money: {amount: 100, currency: gems}}: deposits money into the
 * run's player's balance of a named currency (logic.md §18). With no {@code currency:} it uses the
 * {@code default_currency} setting, which is {@code materione:money} (Vault) unless changed.
 *
 * <p>An unknown currency fails the step with {@code unknown currency}; a currency whose integration is
 * not installed fails with the reason the catalog gives. The headline {@link #threading()} is GLOBAL (the
 * common case, Vault); the step's real thread is the bound currency's, see {@link #threading(Params)}.
 */
public final class GiveMoneyAction implements ActionType<GiveMoneyAction.Params> {

    /** The parsed step; {@code currency} is the authored name, or null for the default. */
    public record Params(double amount, String currency) {}

    private final LogicProviders providers;
    private final CurrencyReads reads;

    /** @param providers the provider slots, read on every run and availability check */
    public GiveMoneyAction(LogicProviders providers) {
        this(providers, CurrencyReads.paper());
    }

    public GiveMoneyAction(LogicProviders providers, CurrencyReads reads) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override
    public String id() {
        return "give_money";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Give money",
                "Adds money to the player's balance of a currency (the default currency unless one is named)",
                List.of(
                        new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                                "Amount to give; above 0", null, null),
                        new ParameterSchema("currency", ParameterType.STRING, false, null,
                                "Currency id, e.g. gems or shop:gems; the default currency when omitted", null, null)),
                "2.0.0"), LogicCategory.ECONOMY, "amount");
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    /**
     * The thread of the currency this step names (logic.md §18): the backend's own thread, so an XP currency
     * runs on the player's region and a Vault economy on the global thread. A currency that is unknown or
     * not bound right now reports {@code PLAYER_REGION}; the step then fails with its reason, so the hop
     * costs nothing that matters. Currencies are live state, which is why this is resolved per step and not
     * at parse.
     */
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
        return switch (backend.deposit(player.getUniqueId(), params.amount())) {
            case EconomyResult.Succeeded ignored -> ActionOutcome.OK;
            case EconomyResult.InsufficientFunds ignored -> ActionOutcome.failed("the currency refused the deposit");
            case EconomyResult.Failed failed -> ActionOutcome.failed(failed.reason());
        };
    }

    /** Available when the default currency can be used; the reason names what is missing otherwise. */
    @Override
    public Availability availability() {
        CurrencyCatalog catalog = providers.currencies();
        Availability own = reads.availabilityResolved(catalog, catalog.defaultCurrency());
        return own.available() ? Availability.AVAILABLE : own;
    }
}
