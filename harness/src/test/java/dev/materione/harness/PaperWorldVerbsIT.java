package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The entity and world verbs on a real Paper server (logic.md §41, #329): a 3 by 3 break through the player's own break path
 * that a protection stand-in vetoes for one block, and a dash that moves the player. See {@link WorldVerbsRunner}.
 */
@Tag("integration")
class PaperWorldVerbsIT {

    @Test
    void aThreeByThreeBreakRespectsAProtectedBlockAndADashMovesThePlayer() {
        HarnessResult result = new WorldVerbsRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
