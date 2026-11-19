package dev.materione.integrations.tokenmanager;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.integrations.currency.WholeAmounts;
import java.util.OptionalLong;
import java.util.UUID;
import me.realized.tokenmanager.api.TokenManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * TokenManager's tokens as one currency's money (logic.md §18). TokenManager's plugin object is its API, so
 * it is looked up at each call. Its reads and its player-based changes need the player online, so an
 * offline player is refused with a reason.
 *
 * <p>Tokens are whole numbers: a fractional amount is refused, never rounded. TokenManager reports a failed
 * change only as {@code false}, so a withdrawal checks the balance first.
 */
final class TokenManagerCurrencyBackend implements CurrencyBackend {

    private static final String NOT_RUNNING = "TokenManager is not enabled";
    private static final String OFFLINE = "TokenManager tokens need the player online";

    @Override
    public Availability availability() {
        return api() != null ? Availability.AVAILABLE : Availability.unavailable(NOT_RUNNING);
    }

    @Override
    public Object readBindingIdentity() {
        return Bukkit.getPluginManager().getPlugin(TokenManagerCurrencyIntegration.PLUGIN);
    }

    @Override
    public EconomyResult balance(UUID id) {
        TokenManager api = api();
        if (api == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        Player player = Bukkit.getPlayer(id);
        return player == null ? new EconomyResult.Failed(OFFLINE) : new EconomyResult.Succeeded(tokens(api, player));
    }

    @Override
    public EconomyResult has(UUID id, double amount) {
        EconomyResult balance = balance(id);
        if (!(balance instanceof EconomyResult.Succeeded held)) {
            return balance;
        }
        return held.balance() >= amount ? held : new EconomyResult.InsufficientFunds(held.balance());
    }

    @Override
    public EconomyResult withdraw(UUID id, double amount) {
        TokenManager api = api();
        if (api == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return new EconomyResult.Failed(OFFLINE);
        }
        OptionalLong whole = WholeAmounts.of(amount, Long.MAX_VALUE);
        if (whole.isEmpty()) {
            return new EconomyResult.Failed(WholeAmounts.refusal("TokenManager", amount));
        }
        long balance = tokens(api, player);
        if (balance < whole.getAsLong()) {
            return new EconomyResult.InsufficientFunds(balance);
        }
        return api.removeTokens(player, whole.getAsLong())
                ? new EconomyResult.Succeeded(tokens(api, player))
                : new EconomyResult.Failed("TokenManager refused the withdrawal");
    }

    @Override
    public EconomyResult deposit(UUID id, double amount) {
        TokenManager api = api();
        if (api == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return new EconomyResult.Failed(OFFLINE);
        }
        OptionalLong whole = WholeAmounts.of(amount, Long.MAX_VALUE);
        if (whole.isEmpty()) {
            return new EconomyResult.Failed(WholeAmounts.refusal("TokenManager", amount));
        }
        return api.addTokens(player, whole.getAsLong())
                ? new EconomyResult.Succeeded(tokens(api, player))
                : new EconomyResult.Failed("TokenManager refused the deposit");
    }

    private static long tokens(TokenManager api, Player player) {
        return api.getTokens(player).orElse(0L);
    }

    private static TokenManager api() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(TokenManagerCurrencyIntegration.PLUGIN);
        return plugin instanceof TokenManager api && plugin.isEnabled() ? api : null;
    }
}
