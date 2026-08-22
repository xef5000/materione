package dev.materione.pack.compile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pack-format policy: the floor never moves without a deliberate bump, the
 * advertised range spans it, and unknown versions are flagged — not guessed.
 */
class PackFormatsTest {

    @Test
    void specIsFloorBasedAndBounded() {
        assertThat(PackFormats.FLOOR).isEqualTo(46); // 1.21.4 (D10)
        assertThat(PackFormats.spec().format()).isEqualTo(PackFormats.FLOOR);
        assertThat(PackFormats.spec().min()).isEqualTo(PackFormats.FLOOR);
        assertThat(PackFormats.spec().max()).isGreaterThan(PackFormats.FLOOR);
        assertThat(PackFormats.MAX_KNOWN_FORMAT).isEqualTo(PackFormats.spec().max());
    }

    @Test
    void knownVersionsSpanTheTable() {
        assertThat(PackFormats.isKnown("1.21.4")).isTrue();
        assertThat(PackFormats.isKnown("1.21.8")).isTrue();
        assertThat(PackFormats.isKnown("26.2")).isTrue();
        // null/blank and versions outside the table are all "unknown"
        assertThat(PackFormats.isKnown(null)).isFalse();
        assertThat(PackFormats.isKnown("")).isFalse();
        assertThat(PackFormats.isKnown("1.21.3")).isFalse();
        assertThat(PackFormats.isKnown("99.99")).isFalse();
    }
}
