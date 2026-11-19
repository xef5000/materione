package dev.materione.integrations.ultraeconomy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import java.util.Optional;
import java.util.UUID;
import me.TechsCode.UltraEconomy.UltraEconomy;
import me.TechsCode.UltraEconomy.UltraEconomyAPI;
import me.TechsCode.UltraEconomy.objects.Account;
import me.TechsCode.UltraEconomy.objects.Balance;
import me.TechsCode.UltraEconomy.objects.Currency;
import org.bukkit.Bukkit;

/**
 * One UltraEconomy currency, on the bank or on the hand, as a Materione currency's money (logic.md §18).
 * Looked up at each call. UltraEconomy answers by account, and a player with no account yet has no money
 * to read, so that is a failure with a reason rather than a zero balance.
 *
 * <p>UltraEconomy's balance setters do not refuse an overdraft, so a withdrawal checks the balance first.
 */
final class UltraEconomyCurrencyBackend implements CurrencyBackend {

    private static final String NOT_RUNNING = "UltraEconomy is not enabled";

    private final String currencyName;
    private final boolean hand;

    UltraEconomyCurrencyBackend(String currencyName, boolean hand) {
        this.currencyName = currencyName;
        this.hand = hand;
    }

    @Override
    public Availability availability() {
        UltraEconomyAPI api = api();
        if (api == null) {
            return Availability.unavailable(NOT_RUNNING);
        }
        return api.getCurrencies().name(currencyName).isPresent()
                ? Availability.AVAILABLE
                : Availability.unavailable("UltraEconomy has no currency named " + currencyName);
    }

    @Override
    public Object readBindingIdentity() {
        return api();
    }

    @Override
    public EconomyResult balance(UUID player) {
        Held held = held(player);
        return held.failure != null ? held.failure : new EconomyResult.Succeeded(held.amount());
    }

    @Override
    public EconomyResult has(UUID player, double amount) {
        Held held = held(player);
        if (held.failure != null) {
            return held.failure;
        }
        double balance = held.amount();
        return balance >= amount ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
    }

    @Override
    public EconomyResult withdraw(UUID player, double amount) {
        Held held = held(player);
        if (held.failure != null) {
            return held.failure;
        }
        double balance = held.amount();
        if (balance < amount) {
            return new EconomyResult.InsufficientFunds(balance);
        }
        if (hand) {
            held.balance.removeHand(amount);
        } else {
            held.balance.removeBank(amount);
        }
        return new EconomyResult.Succeeded(held.amount());
    }

    @Override
    public EconomyResult deposit(UUID player, double amount) {
        Held held = held(player);
        if (held.failure != null) {
            return held.failure;
        }
        if (hand) {
            held.balance.addHand(amount);
        } else {
            held.balance.addBank(amount);
        }
        return new EconomyResult.Succeeded(held.amount());
    }

    private static UltraEconomyAPI api() {
        if (!Bukkit.getPluginManager().isPluginEnabled(UltraEconomyCurrencyIntegration.PLUGIN)) {
            return null;
        }
        return UltraEconomy.getAPI();
    }

    private Held held(UUID player) {
        UltraEconomyAPI api = api();
        if (api == null) {
            return Held.failed(NOT_RUNNING);
        }
        Optional<Currency> currency = api.getCurrencies().name(currencyName);
        if (currency.isEmpty()) {
            return Held.failed("UltraEconomy has no currency named " + currencyName);
        }
        Optional<Account> account = api.getAccounts().uuid(player);
        if (account.isEmpty()) {
            return Held.failed("UltraEconomy has no account for that player yet");
        }
        return new Held(account.get().getBalance(currency.get()), hand, null);
    }

    private record Held(Balance balance, boolean hand, EconomyResult failure) {

        static Held failed(String reason) {
            return new Held(null, false, new EconomyResult.Failed(reason));
        }

        double amount() {
            return hand ? balance.getOnHand() : balance.getOnBank();
        }
    }
}
