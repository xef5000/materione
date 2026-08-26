package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The M3 transport gate, live on Paper (docs/roadmap.md M3): the embedded
 * editor listener boots on loopback, `/materione editor` hands out a
 * capability URL, a real WebSocket client completes the signed handshake
 * after console approval, and sealed frames round-trip. Tolerated errors:
 * none expected — a violation here is an MTR-0033-class bug.
 */
@Tag("integration")
class PaperEditorHandshakeIT {

    @Test
    void editorHandshakeWorksLiveOnPaper() {
        HarnessResult result = new EditorHandshakeRunner().run(HarnessOptions.fromProperties());
        assertThat(result.summary()).contains("harness run OK");
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
