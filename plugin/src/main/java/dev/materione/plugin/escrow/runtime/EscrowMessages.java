package dev.materione.plugin.escrow.runtime;

import java.util.function.BinaryOperator;

/**
 * The player-facing lines of the escrow (gui-input-durability.md §7), keyed {@code gui.escrow.*}. The English defaults
 * are the accepted copy (owner answer 5); a server may override each through the settings key {@code message_<key>}
 * with dots as underscores (for example {@code message_gui_escrow_unavailable}), read with a fallback and never
 * written into a fresh settings file (as the ledger options are). {@code {count}} is replaced by a number.
 */
public record EscrowMessages(
        String unavailable, String busy, String owed, String recovered, String recoveredOwed, String claimed,
        String claimNothing, String claimStillOwed, String loading, String blocked) {

    /** The shipped copy. */
    public static EscrowMessages defaults() {
        return new EscrowMessages(
                "This slot is unavailable on this server.",
                "This slot is busy.",
                "{count} item(s) did not fit and are owed to you: make room and use /materione claim.",
                "{count} item(s) from before the restart were returned to your inventory.",
                "{count} item(s) are owed to you because your inventory is full: use /materione claim.",
                "You received {count} owed item(s).",
                "You are not owed anything.",
                "{count} item(s) are still owed: make room and try again.",
                "Your saved items are still loading; try again in a moment.",
                "Your saved items could not be read; ask an admin before using item slots.");
    }

    /** The defaults, each overridable by its settings key; {@code lookup} answers (key, default) with the value. */
    public static EscrowMessages from(BinaryOperator<String> lookup) {
        EscrowMessages defaults = defaults();
        return new EscrowMessages(
                lookup.apply("message_gui_escrow_unavailable", defaults.unavailable),
                lookup.apply("message_gui_escrow_busy", defaults.busy),
                lookup.apply("message_gui_escrow_owed", defaults.owed),
                lookup.apply("message_gui_escrow_recovered", defaults.recovered),
                lookup.apply("message_gui_escrow_recovered_owed", defaults.recoveredOwed),
                lookup.apply("message_gui_escrow_claimed", defaults.claimed),
                lookup.apply("message_gui_escrow_claim_nothing", defaults.claimNothing),
                lookup.apply("message_gui_escrow_claim_still_owed", defaults.claimStillOwed),
                lookup.apply("message_gui_escrow_loading", defaults.loading),
                lookup.apply("message_gui_escrow_blocked", defaults.blocked));
    }

    /** {@code template} with {@code {count}} replaced. */
    public static String count(String template, int count) {
        return template.replace("{count}", Integer.toString(count));
    }
}
