package dev.materione.core.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;

class QuarantineTrackerTest {

    @Test
    void addListClear() {
        QuarantineTracker tracker = new QuarantineTracker();
        tracker.add(
                NamespacedId.of("mystuff", "broken_item"),
                Diagnostic.error(DiagnosticCodes.PHASE_FAILED, "unresolved model"));
        tracker.add(
                NamespacedId.of("mystuff", "broken_block"),
                Diagnostic.error(DiagnosticCodes.PHASE_FAILED, "no texture"));

        assertThat(tracker.size()).isEqualTo(2);
        assertThat(tracker.all())
                .extracting(q -> q.resource().toString())
                .containsExactly("mystuff:broken_item", "mystuff:broken_block");

        tracker.clear();
        assertThat(tracker.size()).isZero();
        assertThat(tracker.all()).isEmpty();
    }
}
