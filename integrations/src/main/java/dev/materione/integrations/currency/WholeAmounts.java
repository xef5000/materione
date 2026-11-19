package dev.materione.integrations.currency;

import java.util.OptionalLong;

/**
 * Amounts for currencies that only hold whole numbers (PlayerPoints keeps an int).
 * An amount with a fraction is refused rather than rounded: the engine's contract is that a failed
 * money step changes nothing, and a silently truncated price would charge less than the author wrote.
 */
public final class WholeAmounts {

    private WholeAmounts() {}

    /** The amount as a whole number no larger than {@code max}, or empty when it has a fraction or is too large. */
    public static OptionalLong of(double amount, long max) {
        if (!Double.isFinite(amount) || amount != Math.rint(amount) || amount < 0 || amount > max) {
            return OptionalLong.empty();
        }
        return OptionalLong.of((long) amount);
    }

    /** The reason text a backend reports for an amount {@link #of} refused. */
    public static String refusal(String currency, double amount) {
        return currency + " holds whole numbers only, so " + amount + " cannot be used";
    }
}
