package dev.materione.integrations.vault;

import dev.materione.api.logic.provider.EconomyProvider;
import dev.materione.api.logic.provider.EconomyResult;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * The economy Vault exposes, through Vault's own service registry (logic.md §4.5). Looked up at
 * each call, so an economy plugin that registers after Vault is picked up without a reload.
 *
 * <p>Vault cannot say "insufficient funds" as a type, only as a failed response, so a withdrawal
 * checks the balance first and refuses before touching it. Loaded only after Vault is confirmed
 * enabled ({@link dev.materione.integrations.Integrations}).
 */
public final class VaultEconomyProvider implements EconomyProvider {

    /**
     * Loads Vault's {@code Economy} type now. An absent or unloadable Vault fails here, inside the
     * {@code Integrations} guard that reports it, instead of later inside an action.
     */
    public VaultEconomyProvider() {
        Economy.class.getName();
    }

    @Override
    public String name() {
        return "Vault";
    }

    @Override
    public boolean available() {
        return Bukkit.getServicesManager().load(Economy.class) != null;
    }

    @Override
    public EconomyResult balance(UUID player) {
        Economy economy = economy();
        if (economy == null) {
            return new EconomyResult.Failed("no economy registered with Vault");
        }
        return new EconomyResult.Succeeded(economy.getBalance(offline(player)));
    }

    @Override
    public EconomyResult has(UUID player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return new EconomyResult.Failed("no economy registered with Vault");
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
            return new EconomyResult.Failed("no economy registered with Vault");
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
            return new EconomyResult.Failed("no economy registered with Vault");
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
