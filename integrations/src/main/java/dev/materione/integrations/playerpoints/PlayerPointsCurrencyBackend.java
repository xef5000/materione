package dev.materione.integrations.playerpoints;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.integrations.currency.WholeAmounts;
import java.util.OptionalLong;
import java.util.UUID;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;

/**
 * PlayerPoints' balance as one currency's money (logic.md §18). The API is looked up at each call, so a
 * PlayerPoints that reloads is picked up without a reload of Materione. PlayerPoints addresses players
 * by UUID, so an offline player works.
 *
 * <p>Points are whole numbers: a fractional amount is refused, never rounded. PlayerPoints' {@code take}
 * does not say "insufficient", so a withdrawal checks the balance first.
 */
final class PlayerPointsCurrencyBackend implements CurrencyBackend {

    private static final String NOT_RUNNING = "PlayerPoints is not enabled";

    @Override
    public Availability availability() {
        return api() != null ? Availability.AVAILABLE : Availability.unavailable(NOT_RUNNING);
    }

    @Override
    public Object readBindingIdentity() {
        return PlayerPoints.getInstance();
    }

    @Override
    public EconomyResult balance(UUID player) {
        PlayerPointsAPI api = api();
        return api == null ? new EconomyResult.Failed(NOT_RUNNING) : new EconomyResult.Succeeded(api.look(player));
    }

    @Override
    public EconomyResult has(UUID player, double amount) {
        PlayerPointsAPI api = api();
        if (api == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        int balance = api.look(player);
        return balance >= amount ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
    }

    @Override
    public EconomyResult withdraw(UUID player, double amount) {
        PlayerPointsAPI api = api();
        if (api == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        OptionalLong whole = WholeAmounts.of(amount, Integer.MAX_VALUE);
        if (whole.isEmpty()) {
            return new EconomyResult.Failed(WholeAmounts.refusal("PlayerPoints", amount));
        }
        int balance = api.look(player);
        if (balance < whole.getAsLong()) {
            return new EconomyResult.InsufficientFunds(balance);
        }
        return api.take(player, (int) whole.getAsLong())
                ? new EconomyResult.Succeeded(api.look(player))
                : new EconomyResult.Failed("PlayerPoints refused the withdrawal");
    }

    @Override
    public EconomyResult deposit(UUID player, double amount) {
        PlayerPointsAPI api = api();
        if (api == null) {
            return new EconomyResult.Failed(NOT_RUNNING);
        }
        OptionalLong whole = WholeAmounts.of(amount, Integer.MAX_VALUE);
        if (whole.isEmpty()) {
            return new EconomyResult.Failed(WholeAmounts.refusal("PlayerPoints", amount));
        }
        return api.give(player, (int) whole.getAsLong())
                ? new EconomyResult.Succeeded(api.look(player))
                : new EconomyResult.Failed("PlayerPoints refused the deposit");
    }

    private static PlayerPointsAPI api() {
        if (!Bukkit.getPluginManager().isPluginEnabled(PlayerPointsCurrencyIntegration.PLUGIN)) {
            return null;
        }
        PlayerPoints plugin = PlayerPoints.getInstance();
        return plugin == null ? null : plugin.getAPI();
    }
}
