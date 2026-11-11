package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Rows R1 and R3 of gui-input-durability.md (#306): probe P4 (a fake-connection player on the 1.21.4 floor, saved and
 * reloaded by every save path) and P3 (death with both {@code keepInventory} values). See {@link InputDurabilityRunner}
 * and {@link DeathProbeRunner}. The kill rows (probe P2 and the K-row skeleton) moved to {@link PaperEscrowCrashIT}
 * with row H (#313), against the product's own account.
 *
 * <p>Opt-in ({@code -Pharness.inputDurability=true}): every scenario boots the server two to five times (about thirty
 * seconds each).
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
class PaperInputDurabilityIT {

    @Test
    void aFakeConnectionPlayerIsSavedAndReloadedByEverySavePath() {
        HarnessResult result = new InputDurabilityRunner().platformPaths(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    void aWindowReleasedAroundADeathFollowsTheMeasuredPathForBothKeepInventoryValues() {
        HarnessResult result = new DeathProbeRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
