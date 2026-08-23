package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The throttled inventory-scan gate: first event in a gap wins, later ones collapse. */
class ItemAutoUpdaterScanTest {

    @Test
    void firstEventIsDueAndLaterOnesCollapseInsideTheGap() {
        assertThat(ItemAutoUpdater.due(null, 5_000)).isTrue(); // never scanned
        assertThat(ItemAutoUpdater.due(1_000L, 5_000)).isTrue(); // gap elapsed
        assertThat(ItemAutoUpdater.due(4_500L, 5_000)).isFalse(); // inside the gap
        assertThat(ItemAutoUpdater.due(4_000L, 5_000)).isTrue(); // exactly one gap
    }
}
