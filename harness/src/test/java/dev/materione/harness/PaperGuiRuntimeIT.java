package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The M6 runtime gate, live on Paper (gui.md §5, §7): a GUI goes live at boot, the console's
 * {@code gui open} surface answers for it, the editor's preview op refuses a session with no approved
 * player, and the editor's own saves drive the live refresh. The in-game window is judged by the unit
 * tiers and the QA catalog; see {@link GuiRuntimeRunner}.
 */
@Tag("integration")
class PaperGuiRuntimeIT {

    @Test
    void guiRuntimeWorksLiveOnPaper() {
        HarnessResult result = new GuiRuntimeRunner().run(HarnessOptions.fromProperties());
        // The summary carries every recorded problem + the log tail, so a failed gate names its step.
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
