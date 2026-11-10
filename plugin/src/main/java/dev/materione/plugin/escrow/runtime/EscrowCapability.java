package dev.materione.plugin.escrow.runtime;

import java.util.Optional;

/**
 * Whether this server can give {@code durable: required} input layers their guarantees
 * (gui-input-durability.md §5.8): the checks, computed at enable and again on every reload, and what to say when one
 * fails. A failed check is an explicit blocker, never a silent degrade: every required layer is refused (its slots
 * accept nothing), the failed check is named once ({@code MTR-0100}) and listed in {@code /materione doctor};
 * {@code best_effort} layers keep today's behaviour and are labelled as such.
 *
 * <p>Folia is supported (probe P1, §11): the platform check admits it; the Folia consequences are in how the runtime
 * schedules its steps, not in a refusal.
 *
 * @param failed the first check that failed, {@link Check#NONE} when the platform has checkpoint support
 * @param detail what to fix; empty when supported
 */
public record EscrowCapability(Check failed, String detail) {

    /** The checks of §5.8, in the order they are reported. */
    public enum Check {
        /** Every check holds. */
        NONE("supported"),
        /** The platform is not one whose save ordering was read (Paper 1.21.4, 26.1.2, Folia): no descriptor. */
        PLATFORM("unsupported platform"),
        /** {@code spigot.yml players.disable-saving} is on: a saved snapshot is the whole point (F1, F14). */
        SAVING_DISABLED("player-data saving is disabled"),
        /** The ledger writer did not start (the {@code escrow-ledger} boot phase degraded). */
        LEDGER_UNAVAILABLE("the escrow ledger is unavailable"),
        /** {@code storage/escrow/} cannot be created or written (the create-and-delete probe failed). */
        DIRECTORY_NOT_WRITABLE("the escrow directory is not writable"),
        /** The directory probe is still running (it runs off-thread at enable): refused until it finishes. */
        PROBING("the escrow directory is still being checked");

        private final String label;

        Check(String label) {
            this.label = label;
        }

        /** A short name for the doctor line and the diagnostic. */
        public String label() {
            return label;
        }
    }

    /** What the capability was checked against. */
    public record Facts(boolean platformKnown, boolean savingDisabled, boolean ledgerStarted,
            Optional<String> directoryProbe, boolean probeFinished) {}

    /** The pure decision of §5.8: the first failed check wins. */
    public static EscrowCapability evaluate(Facts facts) {
        if (!facts.platformKnown()) {
            return failed(Check.PLATFORM, "run a supported build (Paper 1.21.4 or 26.1.2, or Folia)");
        }
        if (facts.savingDisabled()) {
            return failed(Check.SAVING_DISABLED,
                    "enable player-data saving: set players.disable-saving to false in spigot.yml");
        }
        if (!facts.ledgerStarted()) {
            return failed(Check.LEDGER_UNAVAILABLE,
                    "the escrow-ledger boot phase degraded: see the startup log, then restart");
        }
        if (!facts.probeFinished()) {
            return failed(Check.PROBING, "wait for the check to finish; it runs at enable");
        }
        if (facts.directoryProbe().isPresent()) {
            return failed(Check.DIRECTORY_NOT_WRITABLE,
                    "make storage/escrow/ writable (" + facts.directoryProbe().get() + ")");
        }
        return new EscrowCapability(Check.NONE, "");
    }

    private static EscrowCapability failed(Check check, String detail) {
        return new EscrowCapability(check, detail);
    }

    /** True when {@code durable: required} layers are served. */
    public boolean supported() {
        return failed == Check.NONE;
    }

    /** The sentence for the diagnostic and the doctor line. */
    public String describe() {
        return supported()
                ? "crash-safe input slots: supported (account in the player's data, ledger in storage/escrow/)"
                : "crash-safe input slots: UNAVAILABLE, " + failed.label() + "; durable: required layers are refused, "
                        + "best_effort layers are unchanged. Fix: " + detail;
    }
}
