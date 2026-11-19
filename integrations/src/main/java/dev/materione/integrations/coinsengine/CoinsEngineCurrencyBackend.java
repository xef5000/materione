package dev.materione.integrations.coinsengine;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * One CoinsEngine currency as a Materione currency's money (logic.md §18). The API and the currency are
 * looked up at each call, so a currency CoinsEngine adds later, or a reload of CoinsEngine, is picked up
 * without a reload of Materione. The API is reached through {@link CoinsEngineApi}.
 *
 * <p>CoinsEngine's synchronous operations take a {@link Player}, so an offline player is refused with a
 * reason. Its withdrawal reports only success or failure, so the balance is checked first and a short
 * balance is {@link EconomyResult.InsufficientFunds}, not a failure.
 */
final class CoinsEngineCurrencyBackend implements CurrencyBackend {

    private static final String NOT_RUNNING = "CoinsEngine is not running";

    private final String currencyId;

    CoinsEngineCurrencyBackend(String currencyId) {
        this.currencyId = currencyId;
    }

    @Override
    public Availability availability() {
        Resolved resolved = resolve(null);
        return resolved.failure == null ? Availability.AVAILABLE : Availability.unavailable(resolved.failure);
    }

    @Override
    public Object readBindingIdentity() {
        try {
            return CoinsEngineApi.find().map(CoinsEngineApi::identity).orElse(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    @Override
    public EconomyResult balance(UUID id) {
        Resolved resolved = resolve(id);
        return resolved.failure != null ? new EconomyResult.Failed(resolved.failure)
                : new EconomyResult.Succeeded(resolved.balance());
    }

    @Override
    public EconomyResult has(UUID id, double amount) {
        Resolved resolved = resolve(id);
        if (resolved.failure != null) {
            return new EconomyResult.Failed(resolved.failure);
        }
        double balance = resolved.balance();
        return balance >= amount ? new EconomyResult.Succeeded(balance) : new EconomyResult.InsufficientFunds(balance);
    }

    @Override
    public EconomyResult withdraw(UUID id, double amount) {
        Resolved resolved = resolve(id);
        if (resolved.failure != null) {
            return new EconomyResult.Failed(resolved.failure);
        }
        double balance = resolved.balance();
        if (balance < amount) {
            return new EconomyResult.InsufficientFunds(balance);
        }
        return resolved.api.withdraw(resolved.player, resolved.currency, amount)
                ? new EconomyResult.Succeeded(resolved.balance())
                : new EconomyResult.Failed("CoinsEngine refused the withdrawal of " + currencyId);
    }

    @Override
    public EconomyResult deposit(UUID id, double amount) {
        Resolved resolved = resolve(id);
        if (resolved.failure != null) {
            return new EconomyResult.Failed(resolved.failure);
        }
        return resolved.api.deposit(resolved.player, resolved.currency, amount)
                ? new EconomyResult.Succeeded(resolved.balance())
                : new EconomyResult.Failed("CoinsEngine refused the deposit of " + currencyId);
    }

    /**
     * The API, the currency and, when {@code id} is given, the online player; or the reason one of them is
     * missing. A CoinsEngine whose API does not have the methods used is a reason, not an exception.
     */
    private Resolved resolve(UUID id) {
        Optional<CoinsEngineApi> found;
        try {
            found = CoinsEngineApi.find();
        } catch (ReflectiveOperationException | LinkageError e) {
            return Resolved.failed("this CoinsEngine version's API is not supported (" + e + ")");
        }
        if (found.isEmpty()) {
            return Resolved.failed(NOT_RUNNING);
        }
        CoinsEngineApi api = found.get();
        Object currency = api.currency(currencyId);
        if (currency == null) {
            return Resolved.failed("CoinsEngine has no currency named " + currencyId);
        }
        if (id == null) {
            return new Resolved(api, currency, null, null);
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return Resolved.failed("CoinsEngine currencies need the player online");
        }
        return new Resolved(api, currency, player, null);
    }

    private record Resolved(CoinsEngineApi api, Object currency, Player player, String failure) {

        static Resolved failed(String reason) {
            return new Resolved(null, null, null, reason);
        }

        double balance() {
            return api.balance(player, currency);
        }
    }
}
