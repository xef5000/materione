package dev.materione.api.logic.provider;

import java.util.Objects;

/**
 * What an {@link EconomyProvider} call reports (logic.md §4.5). Three answers, because the actions
 * treat them differently: a balance that covers the request, a refusal for lack of funds (nothing
 * was taken, the flow may {@code require} on it), and a provider failure (the economy itself could
 * not answer; the reason is shown in traces and diagnostics).
 */
public sealed interface EconomyResult permits EconomyResult.Succeeded, EconomyResult.InsufficientFunds,
        EconomyResult.Failed {

    /**
     * The operation was done, or (for {@code balance} and {@code has}) the player holds enough.
     *
     * @param balance the player's balance after the operation
     */
    record Succeeded(double balance) implements EconomyResult {}

    /**
     * A withdrawal the balance cannot cover. The provider must have taken nothing.
     *
     * @param balance the player's unchanged balance
     */
    record InsufficientFunds(double balance) implements EconomyResult {}

    /**
     * The provider could not do what was asked.
     *
     * @param reason a short sentence, never blank
     */
    record Failed(String reason) implements EconomyResult {
        public Failed {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("a failed economy result must say why");
            }
        }
    }
}
