package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The M2 exit gate, live on Paper (roadmap.md §M2): a fixture world with
 * one block per state technique, the shaped pair and a full furniture
 * cluster survives reload, restart and world copy — plus the corruption
 * pair (disposable accelerator rebuild vs chunk-PDC truth) and the
 * invalid-rule fail-soft fault. Five boots; the tolerated error marker is
 * the deliberate TAB-YAML fault from the bootstrap scenario's sibling —
 * none here: this scenario injects only rule faults, which are WARNs.
 */
@Tag("integration")
class PaperWorldPersistenceIT {

    @Test
    void worldSurvivesReloadRestartAndCopy() {
        HarnessResult result = new WorldPersistenceRunner().run(HarnessOptions.fromProperties());
        assertThat(result.summary()).contains("harness run OK");
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
