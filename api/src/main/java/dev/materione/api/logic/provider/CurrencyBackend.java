package dev.materione.api.logic.provider;

import dev.materione.api.logic.Availability;
import java.util.UUID;

/**
 * The money of one bound currency for any player (logic.md §4.5, §16). Returned by
 * {@link CurrencyIntegration#bind}; an action never holds one across runs, it asks the catalog again.
 *
 * <p><b>Contract.</b> A withdrawal the balance cannot cover returns
 * {@link EconomyResult.InsufficientFunds} and takes nothing. Only the backend's own fault is
 * {@link EconomyResult.Failed}. Amounts are finite and positive when a caller passes them.
 */
public interface CurrencyBackend {

    /** Whether this backend can answer right now, and why not (the editor and the actions show the reason). */
    default Availability availability() {
        return Availability.AVAILABLE;
    }

    /** The player's balance. */
    EconomyResult balance(UUID player);

    /** {@link EconomyResult.Succeeded} when the player holds at least {@code amount}, else {@link EconomyResult.InsufficientFunds}. */
    EconomyResult has(UUID player, double amount);

    /** Takes {@code amount}, or refuses with {@link EconomyResult.InsufficientFunds} and takes nothing. */
    EconomyResult withdraw(UUID player, double amount);

    /** Gives {@code amount} to the player. */
    EconomyResult deposit(UUID player, double amount);
}
