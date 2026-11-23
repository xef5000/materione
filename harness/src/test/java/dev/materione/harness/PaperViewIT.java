package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code view.refresh} on a real Paper server (views.md sections 3.6 and 5.4): the three selectors against a window that says
 * {@code rerender: off}, driven by real container clicks of a fake-connection player. See {@link ViewRefreshRunner}.
 */
@Tag("integration")
class PaperViewIT {

    @Test
    void viewRefreshRendersTheSelectedViewersAndHonoursRerender() {
        HarnessResult result = new ViewRefreshRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
