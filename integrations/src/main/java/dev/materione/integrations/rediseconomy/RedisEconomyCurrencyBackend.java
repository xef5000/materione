package dev.materione.integrations.rediseconomy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import dev.unnm3d.rediseconomy.api.RedisEconomyAPI;
import dev.unnm3d.rediseconomy.currency.Currency;
import java.util.UUID;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * One RedisEconomy currency as a Materione currency's money (logic.md §18). RedisEconomy addresses accounts
 * by UUID, so an offline player works. The API and the currency are looked up at each call.
 *
 * <p>RedisEconomy's currencies speak Vault's response type, which cannot say "insufficient funds" as a
 * type, so a withdrawal checks the balance first and refuses before touching it.
 */
final class RedisEconomyCurrencyBackend implements CurrencyBackend {

    private static final String NOT_RUNNING = "RedisEconomy is not enabled";

    /** The currency name an author wrote, or null for RedisEconomy's default currency. */
    private final String currencyName;

    RedisEconomyCurrencyBackend(String currencyName) {
        this.currencyName = currencyName;
    }

    @Override
    public Availability availability() {
        if (api() == null) {
            return Availability.unavailable(NOT_RUNNING);
        }
        return currency() != null
                ? Availability.AVAILABLE
                : Availability.unavailable("RedisEconomy has no currency named " + currencyName);
    }

    @Override
    public Object readBindingIdentity() {
        return api();
    }

    @Override
    public EconomyResult balance(UUID player) {
        Currency currency = currency();
        return currency == null ? missing() : new EconomyResult.Succeeded(currency.getBalance(player));
    }

    @Override
    public EconomyResult has(UUID player, double amount) {
        Currency currency = currency();
        if (currency == null) {
            return missing();
        }
        double balance = currency.getBalance(player);
        return balance >= amount ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
    }

    @Override
    public EconomyResult withdraw(UUID player, double amount) {
        Currency currency = currency();
        if (currency == null) {
            return missing();
        }
        double balance = currency.getBalance(player);
        if (balance < amount) {
            return new EconomyResult.InsufficientFunds(balance);
        }
        return response(currency.withdrawPlayer(offline(player), amount), "RedisEconomy refused the withdrawal");
    }

    @Override
    public EconomyResult deposit(UUID player, double amount) {
        Currency currency = currency();
        if (currency == null) {
            return missing();
        }
        return response(currency.depositPlayer(offline(player), amount), "RedisEconomy refused the deposit");
    }

    private static RedisEconomyAPI api() {
        if (!Bukkit.getPluginManager().isPluginEnabled(RedisEconomyCurrencyIntegration.PLUGIN)) {
            return null;
        }
        return RedisEconomyAPI.getAPI();
    }

    /** The named currency, the default one when no name was written, or null when there is none. */
    private Currency currency() {
        RedisEconomyAPI api = api();
        if (api == null) {
            return null;
        }
        return currencyName == null ? api.getDefaultCurrency() : api.getCurrencyByName(currencyName);
    }

    private EconomyResult missing() {
        if (api() == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        return new EconomyResult.Failed("RedisEconomy has no currency named " + currencyName);
    }

    private static OfflinePlayer offline(UUID player) {
        return Bukkit.getOfflinePlayer(player);
    }

    private static EconomyResult response(EconomyResponse response, String fallback) {
        if (response.transactionSuccess()) {
            return new EconomyResult.Succeeded(response.balance);
        }
        String reason = response.errorMessage == null || response.errorMessage.isBlank()
                ? fallback : response.errorMessage;
        return new EconomyResult.Failed(reason);
    }
}
