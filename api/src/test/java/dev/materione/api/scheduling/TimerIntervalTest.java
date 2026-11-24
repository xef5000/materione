package dev.materione.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimerIntervalTest {

    @Test
    void readsTicksAndCompoundDurationsButNeverLessThanOneSecond() {
        assertThat(TimerInterval.parse(20))
                .isEqualTo(new TimerInterval(TimerInterval.Kind.TICKS, 20, "20"));
        assertThat(TimerInterval.parse("1h30m").duration()).isEqualTo(Duration.ofMinutes(90));
        assertThat(TimerInterval.parse("1500ms").duration()).isEqualTo(Duration.ofMillis(1_500));

        assertThatThrownBy(() -> TimerInterval.parse(19)).hasMessageContaining("at least 20");
        assertThatThrownBy(() -> TimerInterval.parse("999ms")).hasMessageContaining("at least 1s");
        assertThatThrownBy(() -> TimerInterval.parse("soon")).hasMessageContaining("invalid duration");
    }
}
