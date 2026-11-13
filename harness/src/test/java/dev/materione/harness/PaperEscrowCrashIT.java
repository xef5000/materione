package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Row H of gui-input-durability.md (#313): the kill-boundary integration test, K1 to K23 of section 5.10, with a real
 * {@code kill -9} of a real Paper 1.21.4 server running the product's own runtime. See {@link EscrowCrashRunner} for
 * what is real and what is crafted, and {@code harness/README.md} for the run commands.
 *
 * <p>{@link #coreRows} is in the default {@code :harness:integrationTest} run (six boots, about three minutes): K1 killed
 * inside a step, K2, K5, K11 (a real consume killed with its INTENT durable: G2) and K18 (fail closed). The other groups are opt-in
 * ({@code -Pharness.inputDurability=true}), about a dozen boots each at most. K23 (the barrier) is {@link #k23TheBarrier}.
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
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void exitRows() {
        HarnessResult result = new EscrowCrashRunner().exits(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }

    /**
     * K23, the barrier (tier 3): an effect-carrying consume never runs its effect before the disk proves a snapshot that
     * holds the consume: killed between {@code saveData()} and the read-back, a read-back that finds an older snapshot,
     * the effect after the proof, and after a disk that recovers.
     */
    @Test
    @EnabledIfSystemProperty(named = "harness.inputDurability", matches = "true")
    void k23TheBarrier() {
        HarnessResult result = new EscrowCrashRunner().barrier(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
