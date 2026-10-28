package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The M1 exit gate, live on Paper: boot with the fixture corpus (the boot
 * count is the scenario's {@code items=} marker), the flat and nested (#191)
 * hot-reload sequences (add → +1, broken file → unchanged with MTR-0007,
 * remove → back to boot), delivery serving, and a clean stop.
 */
@Tag("integration")
class PaperBootstrapIT {

    @Test
    void bootsAnswersAndStopsCleanly() {
        // MTR-0007 is the deliberately injected broken-item diagnostic
        HarnessResult result = new PaperHarnessRunner().run(
                HarnessOptions.fromProperties(), Set.of("MTR-0007"));
        assertThat(result.summary()).contains("harness run OK");
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
