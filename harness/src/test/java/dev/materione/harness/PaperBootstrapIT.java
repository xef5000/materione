package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Happy path: a real (pinned) Paper server boots the built plugin jar and
 * answers every console command with its stable marker. This is the M0 exit
 * gate's "plugin loads on Paper" proof.
 */
@Tag("integration")
class PaperBootstrapIT {

    @Test
    void bootsAnswersAndStopsCleanly() {
        HarnessResult result = new PaperHarnessRunner().run(HarnessOptions.fromProperties(), Set.of());
        assertThat(result.summary()).contains("harness run OK");
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
