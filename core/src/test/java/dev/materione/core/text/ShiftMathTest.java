package dev.materione.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The shift-font scheme shared by pack emission and runtime text resolution. */
class ShiftMathTest {

    @Test
    void advancesCoverBothDirectionsByPowersOfTwo() {
        var advances = ShiftMath.advances();
        assertThat(advances.get('\uE101')).isEqualTo(-1);
        assertThat(advances.get('\uE109')).isEqualTo(-256);
        assertThat(advances.get('\uE10A')).isEqualTo(-512);
        assertThat(advances.get('\uE111')).isEqualTo(1);
        assertThat(advances.get('\uE11A')).isEqualTo(512);
        // the gap between the two runs stays unused
        assertThat(advances).doesNotContainKey('\uE110');
        assertThat(advances).doesNotContainKey('\uE11B');
    }

    @Test
    void decomposeSumEqualsTheOffset() {
        for (int offset = -2048; offset <= 2048; offset++) {
            int sum = 0;
            for (ShiftMath.Shift shift : ShiftMath.decompose(offset)) {
                sum += shift.advance();
                assertThat(ShiftMath.advances()).containsEntry(shift.character(), shift.advance());
            }
            assertThat(sum).as("offset %d", offset).isEqualTo(offset);
        }
    }

    @Test
    void decomposeIsGreedyAndShort() {
        assertThat(ShiftMath.decompose(0)).isEmpty();
        assertThat(ShiftMath.decompose(1)).hasSize(1);
        assertThat(ShiftMath.decompose(512)).hasSize(1);
        // 1023 = 512 + 255 + 255 + 1 -> greedy never needs more than 2*MAX_POWER
        assertThat(ShiftMath.decompose(1023).size()).isLessThanOrEqualTo(20);
        List<ShiftMath.Shift> shifts = ShiftMath.decompose(-5);
        assertThat(shifts).hasSize(2); // -4, -1
    }

    @Test
    void extremeOffsetsSaturateInsteadOfLooping() {
        int sum = ShiftMath.decompose(Integer.MAX_VALUE).stream()
                .mapToInt(ShiftMath.Shift::advance)
                .sum();
        assertThat(sum).isEqualTo(ShiftMath.MAX_OFFSET);
    }
}
