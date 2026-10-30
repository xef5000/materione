package dev.materione.api.logic.provider;

import java.util.UUID;

/**
 * The money an economy holds for each player (logic.md §4.5; the {@code give_money},
 * {@code take_money} actions and the {@code has_money} condition). Vault is the adapter that
 * ships in {@code integrations}; an addon may supply its own economy the same way.
 *
 * <p><b>Contract.</b> Amounts are finite and positive when a caller passes them. A withdrawal the
 * balance cannot cover returns {@link EconomyResult.InsufficientFunds} and takes nothing; only a
 * provider fault is a {@link EconomyResult.Failed}. Implementations may be called from the thread
 * the calling action declares (economies are not region-safe, so those actions run on the global
 * thread) and must not retain the call.
 */
public interface EconomyProvider {

    /** The provider's display name, such as {@code Vault}. */
    String name();

    /**
     * Whether this provider can answer right now. Read each time the vocabulary is built and each
     * time an action runs, so an economy plugin that starts later is picked up without a reload
     * of Materione.
     */
    default boolean available() {
        return true;
    }

    /** The player's balance. */
    EconomyResult balance(UUID player);

    /** {@link EconomyResult.Succeeded} when the player holds at least {@code amount}, else {@link EconomyResult.InsufficientFunds}. */
    EconomyResult has(UUID player, double amount);

    /** Takes {@code amount} from the player, or refuses with {@link EconomyResult.InsufficientFunds} and takes nothing. */
    EconomyResult withdraw(UUID player, double amount);

    /** Gives {@code amount} to the player. */
    EconomyResult deposit(UUID player, double amount);
}
