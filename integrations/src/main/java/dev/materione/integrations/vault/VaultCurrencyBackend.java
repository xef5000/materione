package dev.materione.integrations.vault;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * The Vault economy as one currency's money (logic.md §18). Looked up at each call through Vault's
 * service registry, so an economy plugin that registers after Vault is picked up without a reload.
 *
 * <p>Vault cannot say "insufficient funds" as a type, only as a failed response, so a withdrawal
 * checks the balance first and refuses before touching it.
 */
final class VaultCurrencyBackend implements CurrencyBackend {

    private static final String NO_ECONOMY = "no economy is registered with Vault";

    @Override
    public Availability availability() {
        return Bukkit.getServicesManager().load(Economy.class) != null
                ? Availability.AVAILABLE
                : Availability.unavailable(NO_ECONOMY + " (install an economy plugin such as EssentialsX)");
    }

    @Override
    public Object readBindingIdentity() {
        return Bukkit.getServicesManager().getRegistration(Economy.class);
    }

    /**
     * The legacy money-action owner is {@code GLOBAL}. That does not establish that an arbitrary
     * economy behind Vault is Folia-safe; read capability remains unsupported by default.
     */
    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    @Override
    public EconomyResult balance(UUID player) {
        Economy economy = economy();
        if (economy == null) {
            return new EconomyResult.Failed(NO_ECONOMY);
        }
        return new EconomyResult.Succeeded(economy.getBalance(offline(player)));
    }

    @Override
    public EconomyResult has(UUID player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return new EconomyResult.Failed(NO_ECONOMY);
        }
        OfflinePlayer account = offline(player);
        double balance = economy.getBalance(account);
        return economy.has(account, amount)
                ? new EconomyResult.Succeeded(balance)
                : new EconomyResult.InsufficientFunds(balance);
    }

    @Override
    public EconomyResult withdraw(UUID player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return new EconomyResult.Failed(NO_ECONOMY);
        }
        OfflinePlayer account = offline(player);
        if (!economy.has(account, amount)) {
            return new EconomyResult.InsufficientFunds(economy.getBalance(account));
        }
        return response(economy.withdrawPlayer(account, amount), "the economy refused the withdrawal");
    }

    @Override
    public EconomyResult deposit(UUID player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return new EconomyResult.Failed(NO_ECONOMY);
        }
        return response(economy.depositPlayer(offline(player), amount), "the economy refused the deposit");
    }

    private static Economy economy() {
        return Bukkit.getServicesManager().load(Economy.class);
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
