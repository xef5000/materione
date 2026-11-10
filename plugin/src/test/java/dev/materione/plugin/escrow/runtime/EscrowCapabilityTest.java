package dev.materione.plugin.escrow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.runtime.EscrowCapability.Check;
import dev.materione.plugin.escrow.runtime.EscrowCapability.Facts;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The capability of gui-input-durability.md section 5.8: each failed check is named, and the first one wins. */
class EscrowCapabilityTest {

    private static final Facts GOOD = new Facts(true, false, true, Optional.empty(), true);

    @Test
    void aSupportedPlatformServesRequiredLayers() {
        EscrowCapability capability = EscrowCapability.evaluate(GOOD);
        assertThat(capability.supported()).isTrue();
        assertThat(capability.failed()).isEqualTo(Check.NONE);
        assertThat(capability.describe()).contains("supported");
    }

    @Test
    void eachFailedCheckIsNamedWithWhatToFix() {
        assertThat(EscrowCapability.evaluate(new Facts(false, false, true, Optional.empty(), true)).failed())
                .isEqualTo(Check.PLATFORM);
        EscrowCapability saving = EscrowCapability.evaluate(new Facts(true, true, true, Optional.empty(), true));
        assertThat(saving.failed()).isEqualTo(Check.SAVING_DISABLED);
        assertThat(saving.detail()).contains("players.disable-saving");
        assertThat(EscrowCapability.evaluate(new Facts(true, false, false, Optional.empty(), true)).failed())
                .isEqualTo(Check.LEDGER_UNAVAILABLE);
        EscrowCapability directory = EscrowCapability.evaluate(
                new Facts(true, false, true, Optional.of("AccessDeniedException: x"), true));
        assertThat(directory.failed()).isEqualTo(Check.DIRECTORY_NOT_WRITABLE);
        assertThat(directory.describe()).contains("refused").contains("best_effort").contains("AccessDenied");
    }

    @Test
    void theDirectoryProbeStillRunningRefusesUntilItFinishes() {
        EscrowCapability capability = EscrowCapability.evaluate(new Facts(true, false, true, Optional.empty(), false));
        assertThat(capability.supported()).isFalse();
        assertThat(capability.failed()).isEqualTo(Check.PROBING);
    }

    @Test
    void theFirstFailedCheckWins() {
        assertThat(EscrowCapability.evaluate(new Facts(false, true, false, Optional.of("x"), false)).failed())
                .isEqualTo(Check.PLATFORM);
    }

    @Test
    void theMessagesCarryTheCountAndCanBeOverridden() {
        EscrowMessages defaults = EscrowMessages.defaults();
        assertThat(EscrowMessages.count(defaults.owed(), 3)).contains("3 item(s)").contains("/materione claim");
        EscrowMessages custom = EscrowMessages.from((key, fallback) ->
                key.equals("message_gui_escrow_unavailable") ? "Nope." : fallback);
        assertThat(custom.unavailable()).isEqualTo("Nope.");
        assertThat(custom.busy()).isEqualTo(defaults.busy());
    }
}
