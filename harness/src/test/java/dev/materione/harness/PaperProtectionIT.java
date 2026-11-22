package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The protection port on a real Paper server (logic.md §43, #466): in {@code protection_mode: both} a stand-in provider's deny stops a
 * {@code break_blocks} block before the event path and an actor-less {@code set_block} is asked with no actor, in
 * {@code event} mode the port is ignored by the verbs, and {@code can_build} reads the port. See {@link ProtectionRunner}.
 */
@Tag("integration")
class PaperProtectionIT {

    @Test
    void aPortDenialStopsABreakBeforeTheEventPathEventModeIgnoresThePortAndCanBuildReadsIt() {
        HarnessResult result = new ProtectionRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
