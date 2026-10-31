package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The open-command gate on Paper (logic.md §6.1, #215, #96): a declared alias is registered at boot and runs,
 * a bad argument answers with a usage line, and a content reload adds and removes aliases without a restart.
 * See {@link OpenCommandRunner}.
 */
@Tag("integration")
class PaperOpenCommandIT {

    @Test
    void openCommandsWorkLiveOnPaper() {
        HarnessResult result = new OpenCommandRunner().run(HarnessOptions.fromProperties());
        // The summary carries every recorded problem + the log tail, so a failed gate names its step.
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
