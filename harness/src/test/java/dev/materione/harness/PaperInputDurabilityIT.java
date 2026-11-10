package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Rows R1 and R3 of gui-input-durability.md (#306): probes P4 (a fake-connection player on the 1.21.4 floor), P2 (a
 * completed save survives {@code destroyForcibly}) and P3 (death with both {@code keepInventory} values), and the K-row
 * skeleton row H (#313) fills in. See {@link InputDurabilityRunner} and {@link DeathProbeRunner}.
 *
 * <p>Opt-in ({@code -Pharness.inputDurability=true}): every scenario boots the server two to five times (about thirty
 * seconds each), so the whole class is several minutes. Row H decides whether the K rows move into the default run.
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
    void killRowsK1K2K4K21SurviveAKillAtTheirCrashGate() {
        HarnessResult result = new InputDurabilityRunner().kRows(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    void aWindowReleasedAroundADeathFollowsTheMeasuredPathForBothKeepInventoryValues() {
        HarnessResult result = new DeathProbeRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
