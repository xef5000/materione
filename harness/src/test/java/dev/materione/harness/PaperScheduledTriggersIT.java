package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class PaperScheduledTriggersIT {

    @Test
    void aOneSecondTimerIncrementsAServerVariable() {
        HarnessResult result = new ScheduledTriggersRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
