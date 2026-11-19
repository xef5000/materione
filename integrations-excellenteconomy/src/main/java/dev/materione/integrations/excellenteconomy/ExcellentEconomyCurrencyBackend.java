package dev.materione.integrations.excellenteconomy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

/**
 * One ExcellentEconomy currency as a Materione currency's money (logic.md §18). The API and the currency are
 * looked up at each call through Bukkit's service registry, so a currency ExcellentEconomy adds later, or a
 * reload of ExcellentEconomy, is picked up without a reload of Materione.
 *
 * <p>ExcellentEconomy's synchronous operations take a {@link Player}, so an offline player is refused with a
 * reason. Its withdrawal reports only success or failure, so the balance is checked first and a short
 * balance is {@link EconomyResult.InsufficientFunds}, not a failure.
 */
final class ExcellentEconomyCurrencyBackend implements CurrencyBackend {

    private static final String NOT_RUNNING = "ExcellentEconomy is not running";
    private static final String OFFLINE = "ExcellentEconomy currencies need the player online";

    private final String currencyId;

    ExcellentEconomyCurrencyBackend(String currencyId) {
        this.currencyId = currencyId;
    }

    @Override
    public Availability availability() {
        ExcellentEconomyAPI api = api();
        if (api == null) {
            return Availability.unavailable(NOT_RUNNING);
        }
        return api.getCurrency(currencyId) != null
                ? Availability.AVAILABLE
                : Availability.unavailable("ExcellentEconomy has no currency named " + currencyId);
    }

    @Override
    public Object readBindingIdentity() {
        return Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
    }

    @Override
    public EconomyResult balance(UUID id) {
        Target target = target(id);
        return target.failure != null ? new EconomyResult.Failed(target.failure)
                : new EconomyResult.Succeeded(target.balance());
    }

    @Override
    public EconomyResult has(UUID id, double amount) {
        Target target = target(id);
        if (target.failure != null) {
            return new EconomyResult.Failed(target.failure);
        }
        double balance = target.balance();
        return balance >= amount ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
    }

    @Override
    public EconomyResult withdraw(UUID id, double amount) {
        Target target = target(id);
        if (target.failure != null) {
            return new EconomyResult.Failed(target.failure);
        }
        double balance = target.balance();
        if (balance < amount) {
            return new EconomyResult.InsufficientFunds(balance);
        }
        return target.api.withdraw(target.player, target.currency, amount)
                ? new EconomyResult.Succeeded(target.balance())
                : new EconomyResult.Failed("ExcellentEconomy refused the withdrawal of " + currencyId);
    }

    @Override
    public EconomyResult deposit(UUID id, double amount) {
        Target target = target(id);
        if (target.failure != null) {
            return new EconomyResult.Failed(target.failure);
        }
        return target.api.deposit(target.player, target.currency, amount)
                ? new EconomyResult.Succeeded(target.balance())
                : new EconomyResult.Failed("ExcellentEconomy refused the deposit of " + currencyId);
    }

    private static ExcellentEconomyAPI api() {
        return Bukkit.getServicesManager().load(ExcellentEconomyAPI.class);
    }

    /** The API, the currency and the online player, or the reason one of them is missing. */
    private Target target(UUID id) {
        ExcellentEconomyAPI api = api();
        if (api == null) {
            return Target.failed(NOT_RUNNING);
        }
        ExcellentCurrency currency = api.getCurrency(currencyId);
        if (currency == null) {
            return Target.failed("ExcellentEconomy has no currency named " + currencyId);
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return Target.failed(OFFLINE);
        }
        return new Target(api, currency, player, null);
    }

    private record Target(ExcellentEconomyAPI api, ExcellentCurrency currency, Player player, String failure) {

        static Target failed(String reason) {
            return new Target(null, null, null, reason);
        }

        double balance() {
            return api.getBalance(player, currency);
        }
    }
}
