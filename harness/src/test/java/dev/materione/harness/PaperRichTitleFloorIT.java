package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in protocol test; not evidence about rendering or physical mouse input. */
@Tag("integration")
@EnabledIfSystemProperty(named = "harness.richTitleFloor", matches = "true")
class PaperRichTitleFloorIT {
    @Test
    void productionRichTitlePreservesThePaperFloorMenuAndState() {
        HarnessResult result = new RichTitleFloorRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
