package su.nightexpress.excellenteconomy.api;

import org.bukkit.entity.Player;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

/** A test double with the real service interface's name and the four method shapes the adapter calls. */
public interface ExcellentEconomyAPI {
    ExcellentCurrency getCurrency(String id);

    double getBalance(Player player, ExcellentCurrency currency);

    boolean deposit(Player player, ExcellentCurrency currency, double amount);

    boolean withdraw(Player player, ExcellentCurrency currency, double amount);
}
