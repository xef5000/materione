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
 * {@code give_money: 100}: deposits money into the run's player's economy balance. Runs against
 * the economy provider resolved at use; with none installed it fails with {@code no economy
 * provider} and {@link #availability()} says so before the step ever runs.
 *
 * <p>Declared {@link Threading#GLOBAL}: economies (Vault and what it wraps) are not region-safe,
 * so they run on the global thread, the main thread on Paper.
 */
public final class GiveMoneyAction implements ActionType<GiveMoneyAction.Params> {

    /** The parsed step. */
    public record Params(double amount) {}

    private final LogicProviders providers;

    /** @param providers the provider slots, read on every run and availability check */
    public GiveMoneyAction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "give_money";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Give money",
                "Adds money to the player's economy balance",
                List.of(new ParameterSchema("amount", ParameterType.DECIMAL, true, null,
                        "Amount to give; above 0", null, null)),
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
        return switch (economy.deposit(player.getUniqueId(), params.amount())) {
            case EconomyResult.Succeeded ignored -> ActionOutcome.OK;
            case EconomyResult.InsufficientFunds ignored -> ActionOutcome.failed("economy refused the deposit");
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
