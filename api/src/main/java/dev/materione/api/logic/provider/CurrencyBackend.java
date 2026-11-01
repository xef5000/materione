package dev.materione.api.logic.provider;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Threading;
import java.util.UUID;

/**
 * The money of one bound currency for any player (logic.md §4.5, §18). Returned by
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

    /**
     * The thread this backend's money calls must run on (logic.md §4.1, §18). A money action reports this
     * for the currency its step names, so the engine hops to the right thread before it calls the backend.
     * The default is {@link Threading#GLOBAL}, the conservative choice: a backend that touches only one
     * player's own state overrides it with {@link Threading#PLAYER_REGION}.
     */
    default Threading threading() {
        return Threading.GLOBAL;
    }

    /**
     * The owner, demonstrated Folia safety, and offline support of {@link #balance} and
     * {@link #has}. An existing integration remains source and binary compatible, but is not
     * assumed safe on Folia merely because {@link #threading()} returns GLOBAL.
     * This declaration is cheap and safe to inspect from any thread; it does not read player state.
     */
    default CurrencyReadCapability readCapability() {
        return CurrencyReadCapability.unsupported(Threading.GLOBAL);
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
