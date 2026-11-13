package dev.materione.plugin.logic.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The clock functions (logic.md §15): now(), today() and format_duration(), driven by a fake clock. */
class ClockFunctionsTest {

    private static final LogicContext EMPTY = new MapLogicContext(Map.of());

    /** 2026-11-12T23:30:00Z: day 20,769 in UTC, and already the 13th (day 20,770) in Paris (UTC+1). */
    private static final Instant LATE_UTC = Instant.parse("2026-11-12T23:30:00Z");

    private static Object call(ExpressionFunction function, Object... arguments) {
        return function.call(Arrays.asList(arguments), EMPTY);
    }

    @Test
    void nowIsTheWholeEpochSecondOfTheClockAndFollowsIt() {
        var clock = new FakeClock(Instant.ofEpochSecond(1_700_000_000L, 999_000_000L), ZoneId.of("UTC"));
        var now = new NowFunction(clock);

        assertThat(call(now)).isEqualTo(1_700_000_000.0); // the 999 ms are rounded down
        clock.advanceSeconds(90);
        assertThat(call(now)).isEqualTo(1_700_000_090.0);
    }

    @Test
    void nowTakesNoArgumentsAndReadsTheClockOnlyWhenCalled() {
        var clock = new FakeClock(LATE_UTC, ZoneId.of("UTC"));
        var now = new NowFunction(clock);

        assertThat(now.describe().minArguments()).isZero();
        assertThat(now.describe().maxArguments()).isZero();
        assertThat(clock.reads).as("describing the function reads nothing").isZero();
        call(now);
        assertThat(clock.reads).isEqualTo(1);
    }

    @Test
    void todayIsTheLocalDayNumberInTheClocksZone() {
        var utc = new TodayFunction(Clock.fixed(LATE_UTC, ZoneId.of("UTC")));
        var paris = new TodayFunction(Clock.fixed(LATE_UTC, ZoneId.of("Europe/Paris")));

        assertThat(call(utc)).isEqualTo(20_769.0);
        assertThat(call(paris)).as("Paris has already started the 13th").isEqualTo(20_770.0);
    }

    @Test
    void todayStaysTheSameAllDayAndChangesAtMidnight() {
        var clock = new FakeClock(Instant.parse("2026-11-12T00:00:00Z"), ZoneId.of("UTC"));
        var today = new TodayFunction(clock);

        double start = (double) call(today);
        clock.advanceSeconds(86_399);
        assertThat(call(today)).isEqualTo(start);
        clock.advanceSeconds(1);
        assertThat(call(today)).isEqualTo(start + 1);
    }

    @Test
    void formatDurationGivesTheDocumentedShapes() {
        var format = new FormatDurationFunction();

        assertThat(call(format, 93_784.0)).isEqualTo("1d 2h"); // 1d 2h 3m 4s: only the two largest units
        assertThat(call(format, 90_061.0)).isEqualTo("1d 1h");
        assertThat(call(format, 200.0)).isEqualTo("3m 20s");
        assertThat(call(format, 45.0)).isEqualTo("45s");
        assertThat(call(format, 3_600.0)).isEqualTo("1h");
        assertThat(call(format, 86_400.0 * 2 + 5)).isEqualTo("2d 5s");
        assertThat(call(format, "120")).as("a numeric string reads as a number").isEqualTo("2m");
    }

    @Test
    void formatDurationRoundsFractionsUpAndShowsZeroForNothing() {
        var format = new FormatDurationFunction();

        assertThat(call(format, 0.3)).as("a wait still running never reads 0s").isEqualTo("1s");
        assertThat(call(format, 59.01)).isEqualTo("1m");
        assertThat(call(format, 0.0)).isEqualTo("0s");
        assertThat(call(format, -5.0)).isEqualTo("0s");
        assertThat(call(format, "not a number")).isNull();
        assertThat(call(format, (Object) null)).isNull();
        assertThat(call(format, Double.NaN)).isNull();
    }

    @Test
    void theClockFunctionsAreRegisteredAsBuiltIns() {
        var ids = BuiltinLogic.functions(Clock.fixed(LATE_UTC, ZoneId.of("UTC"))).stream()
                .map(ExpressionFunction::id).toList();

        assertThat(ids).contains("now", "today", "format_duration");
    }

    @Test
    void noBadArgumentThrows() {
        List<Object> wrong = Arrays.asList(null, true, List.of(), Map.of("k", 1d), "not a number", Double.NaN);
        var functions = List.of(
                new NowFunction(Clock.systemUTC()), new TodayFunction(Clock.systemUTC()), new FormatDurationFunction());

        for (ExpressionFunction function : functions) {
            for (Object value : wrong) {
                assertThatCode(() -> function.call(Arrays.asList(value), EMPTY))
                        .as(function.id() + " with " + value)
                        .doesNotThrowAnyException();
            }
        }
    }

    /** A clock that moves only when the test says so, and counts its reads. */
    private static final class FakeClock extends Clock {

        private Instant now;
        private final ZoneId zone;
        int reads;

        FakeClock(Instant start, ZoneId zone) {
            this.now = start;
            this.zone = zone;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new FakeClock(now, other);
        }

        @Override
        public Instant instant() {
            reads++;
            return now;
        }
    }
}
