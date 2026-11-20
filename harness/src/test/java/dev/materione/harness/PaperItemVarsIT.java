package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Item-scoped variables on a real Paper server (logic.md §38, #333): two swords keep two levels, the level survives a
 * drop, a pick-up and a restart, and stacks whose state differs do not merge. See {@link ItemVarsRunner}.
 */
@Tag("integration")
class PaperItemVarsIT {

    @Test
    void aStackHoldsItsOwnStateThroughADropAPickupAndARestart() {
        HarnessResult result = new ItemVarsRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
