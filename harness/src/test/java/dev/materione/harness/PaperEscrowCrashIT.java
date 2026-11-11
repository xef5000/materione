package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Row H of gui-input-durability.md (#313): the kill-boundary integration test, K1 to K22 of section 5.10, with a real
 * {@code kill -9} of a real Paper 1.21.4 server running the product's own runtime. See {@link EscrowCrashRunner} for
 * what is real and what is crafted, and {@code harness/README.md} for the run commands.
 *
 * <p>{@link #coreRows} is in the default {@code :harness:integrationTest} run (six boots, about three minutes): K1 killed
 * inside a step, K2, K5, K11/K12 (G2 across a kill) and K18 (fail closed). The other groups are opt-in
 * ({@code -Pharness.inputDurability=true}), about a dozen boots each at most. K23 is not run: it needs row B (#311).
 */
@Tag("integration")
class PaperEscrowCrashIT {

    @Test
    void coreRows() {
        HarnessResult result = new EscrowCrashRunner().core(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void tier1Rows() {
        HarnessResult result = new EscrowCrashRunner().tier1(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void deathAndDisableRows() {
        HarnessResult result = new EscrowCrashRunner().deathAndDisable(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void ledgerRows() {
        HarnessResult result = new EscrowCrashRunner().ledger(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void cursorAndFileStateRows() {
        HarnessResult result = new EscrowCrashRunner().cursorAndFiles(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void k19AnAutosaveNeverSeesHalfAClick() {
        HarnessResult result = new EscrowCrashRunner().autosave(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    @Test
    @Disabled("K23, the barrier (tier 3), waits for row B (#311, PR #323, not merged): its gates, the DONE record and the read-back do not exist"
            + " on this branch. Add a barrier gate to CrashGate.Gates beside the saveData call and a row to EscrowCrashRunner"
            + " that kills after saveData() and before the read-back confirms")
    void k23TheBarrier() {
        // pending row B
    }
}
