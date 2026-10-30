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
import dev.materione.api.logic.provider.EconomyProvider;
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
 * {@code take_money: 100}: withdraws money from the run's player. A balance that cannot cover the
 * amount fails the step with {@code insufficient funds} and takes nothing, so a flow can branch on
 * the refusal with {@code require} (logic.md §2.2). Like {@code give_money} it runs against the
 * economy provider resolved at use, and it is {@link Threading#GLOBAL}.
 */
public final class TakeMoneyAction implements ActionType<TakeMoneyAction.Params> {

    /** The parsed step. */
    public record Params(double amount) {}

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
                "Removes money from the player's economy balance; fails when the balance is too low",
                List.of(new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                        "Amount to take; above 0", null, null)),
                "2.0.0"), LogicCategory.ECONOMY, "amount");
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(ProviderParams.amount(params));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        EconomyProvider economy = providers.economy().filter(EconomyProvider::available).orElse(null);
        if (economy == null) {
            return ActionOutcome.failed("no economy provider");
        }
        return switch (economy.withdraw(player.getUniqueId(), params.amount())) {
            case EconomyResult.Succeeded ignored -> ActionOutcome.OK;
            case EconomyResult.InsufficientFunds funds -> ActionOutcome.failed(
                    "insufficient funds: balance " + funds.balance() + ", needs " + params.amount());
            case EconomyResult.Failed failed -> ActionOutcome.failed(failed.reason());
        };
    }

    @Override
    public Availability availability() {
        return providers.economy().filter(EconomyProvider::available).isPresent()
                ? Availability.AVAILABLE
                : Availability.unavailable("No economy provider is installed (Vault with an economy plugin)");
    }
}
