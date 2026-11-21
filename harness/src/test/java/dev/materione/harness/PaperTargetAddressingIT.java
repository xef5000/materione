package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Target addressing on a real Paper server (logic.md §39, #331): a potion on the struck entity (the bleed), a message to
 * a named player and the failure of one to an offline player, and a bounded selector. See
 * {@link TargetAddressingRunner}.
 */
@Tag("integration")
class PaperTargetAddressingIT {

    @Test
    void anActionActsOnTheStruckEntityAndOnANamedPlayerAndAnUnresolvedTargetFails() {
        HarnessResult result = new TargetAddressingRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
