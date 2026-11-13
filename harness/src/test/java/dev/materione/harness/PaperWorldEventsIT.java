package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The world-event triggers and rule documents on a real Paper server (logic.md §37, #327): join, kill (a quest that
 * counts), hit (on a rule document and on an item), a cancelled hit, hurt, death, respawn and quit. See
 * {@link WorldEventsRunner}.
 */
@Tag("integration")
class PaperWorldEventsIT {

    @Test
    void aRuleDocumentAnswersTheWorldAndAnItemAnswersAHit() {
        HarnessResult result = new WorldEventsRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
