package dev.materione.pack.compile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pack-format policy: the floor never moves without a deliberate bump, the
 * advertised range spans it in both schema generations, the server-reported
 * format widens (never shrinks) the bound, and unknown versions are flagged
 * — not guessed.
 */
class PackFormatsTest {

    @Test
    void specIsFloorBasedAndBounded() {
        assertThat(PackFormats.FLOOR).isEqualTo(46); // 1.21.4 (D10)
        assertThat(PackFormats.spec().min().major()).isEqualTo(PackFormats.FLOOR);
        assertThat(PackFormats.spec().max().major()).isGreaterThan(PackFormats.FLOOR);
        assertThat(PackFormats.MAX_KNOWN_FORMAT).isEqualTo(PackFormats.spec().max().major());
    }

    @Test
    void detectedServerFormatWidensButNeverShrinks() {
        // a server newer than the table drags the bound up with it
        assertThat(PackFormats.spec(96).max().major()).isEqualTo(96);
        // an older server (e.g. 1.21.4 reporting 46) keeps the table bound
        assertThat(PackFormats.spec(46).max().major()).isEqualTo(PackFormats.MAX_KNOWN_FORMAT);
        // detection failure (-1) is the plain table spec
        assertThat(PackFormats.spec(-1).max().major()).isEqualTo(PackFormats.MAX_KNOWN_FORMAT);
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

    @Test
    void patchReleasesRideTheirMinorLine() {
        // the live case that shipped this: Paper "26.1.2" vs table "26.1"
        assertThat(PackFormats.isKnown("26.1.2")).isTrue();
        assertThat(PackFormats.isKnown("1.21.4-R0.1")).isFalse(); // only patch digits strip
        assertThat(PackFormats.isKnown("26.3.1")).isFalse(); // unknown minor stays unknown
    }
}
