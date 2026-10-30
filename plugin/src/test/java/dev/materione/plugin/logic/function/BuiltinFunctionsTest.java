package dev.materione.plugin.logic.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The built-in expression functions (logic.md §4.2), each against a fake context. Every function is
 * checked on its happy path and on the wrong kinds of argument, which answer {@code null} or
 * {@code false} and never throw (the data rule).
 */
class BuiltinFunctionsTest {

    private static final LogicContext EMPTY = new MapLogicContext(Map.of());

    private static Object call(ExpressionFunction function, Object... arguments) {
        return function.call(Arrays.asList(arguments), EMPTY);
    }

    private static final ExpressionFunction CONTAINS = new ContainsFunction();
    private static final ExpressionFunction EQUALS_IGNORE_CASE = new EqualsIgnoreCaseFunction();
    private static final ExpressionFunction STARTS_WITH = new StartsWithFunction();
    private static final ExpressionFunction ENDS_WITH = new EndsWithFunction();
    private static final ExpressionFunction MATCHES = new MatchesFunction();
    private static final ExpressionFunction LENGTH = new LengthFunction();
    private static final ExpressionFunction LOWER = new LowerFunction();
    private static final ExpressionFunction UPPER = new UpperFunction();
    private static final ExpressionFunction TRIM = new TrimFunction();
    private static final ExpressionFunction IS_NUMBER = new IsNumberFunction();
    private static final ExpressionFunction NUMBER = new NumberFunction();
    private static final ExpressionFunction ROUND = new RoundFunction();
    private static final ExpressionFunction FLOOR = new FloorFunction();
    private static final ExpressionFunction CEIL = new CeilFunction();
    private static final ExpressionFunction MIN = new MinFunction();
    private static final ExpressionFunction MAX = new MaxFunction();
    private static final ExpressionFunction ABS = new AbsFunction();
    private static final ExpressionFunction RANDOM = new RandomFunction();
    private static final ExpressionFunction FORMAT_NUMBER = new FormatNumberFunction();
    private static final ExpressionFunction IS_PLAYER = new IsPlayerFunction();

    @Nested
    class Text {

        @Test
        void containsIsCaseSensitiveAndFalseForNonText() {
            assertThat(call(CONTAINS, "hello", "ell")).isEqualTo(true);
            assertThat(call(CONTAINS, "Hello", "ell")).isEqualTo(true);
            assertThat(call(CONTAINS, "hello", "ELL")).isEqualTo(false);
            assertThat(call(CONTAINS, "hello", "")).isEqualTo(true);
            assertThat(call(CONTAINS, "room 12", 12d)).isEqualTo(true);
            assertThat(call(CONTAINS, null, "x")).isEqualTo(false);
            assertThat(call(CONTAINS, "x", null)).isEqualTo(false);
            assertThat(call(CONTAINS, List.of("x"), "x")).isEqualTo(false);
        }

        @Test
        void equalsIgnoreCaseComparesWholeTexts() {
            assertThat(call(EQUALS_IGNORE_CASE, "AbC", "abc")).isEqualTo(true);
            assertThat(call(EQUALS_IGNORE_CASE, "abc", "abcd")).isEqualTo(false);
            assertThat(call(EQUALS_IGNORE_CASE, "a", null)).isEqualTo(false);
            assertThat(call(EQUALS_IGNORE_CASE, 1d, "1")).isEqualTo(true);
        }

        @Test
        void startsWithAndEndsWithAreCaseSensitive() {
            assertThat(call(STARTS_WITH, "Alex", "Al")).isEqualTo(true);
            assertThat(call(STARTS_WITH, "Alex", "al")).isEqualTo(false);
            assertThat(call(STARTS_WITH, null, "a")).isEqualTo(false);
            assertThat(call(ENDS_WITH, "Alex", "ex")).isEqualTo(true);
            assertThat(call(ENDS_WITH, "Alex", "EX")).isEqualTo(false);
            assertThat(call(ENDS_WITH, "Alex", null)).isEqualTo(false);
        }

        @Test
        void lengthCountsCodePointsAndIsNullForNonText() {
            assertThat(call(LENGTH, "hello")).isEqualTo(5d);
            assertThat(call(LENGTH, "")).isEqualTo(0d);
            assertThat(call(LENGTH, "😀x")).isEqualTo(2d); // one emoji, one letter
            assertThat(call(LENGTH, 12d)).isEqualTo(2d);
            assertThat(call(LENGTH, (Object) null)).isNull();
            assertThat(call(LENGTH, Map.of())).isNull();
        }

        @Test
        void lowerAndUpperUseTheRootLocale() {
            assertThat(call(LOWER, "ÀBc")).isEqualTo("àbc");
            assertThat(call(UPPER, "àbC")).isEqualTo("ÀBC");
            assertThat(call(LOWER, (Object) null)).isNull();
            assertThat(call(UPPER, true)).isEqualTo("TRUE");
        }

        @Test
        void trimStripsUnicodeWhitespaceAndIsNullForNonText() {
            assertThat(call(TRIM, "  x \t")).isEqualTo("x");
            assertThat(call(TRIM, " y ")).isEqualTo("y");
            assertThat(call(TRIM, (Object) null)).isNull();
            assertThat(call(TRIM, List.of())).isNull();
        }
    }

    @Nested
    class Matching {

        @Test
        void matchesIsAFullMatch() {
            assertThat(call(MATCHES, "abc", "a.c")).isEqualTo(true);
            assertThat(call(MATCHES, "abcd", "a.c")).isEqualTo(false);
            assertThat(call(MATCHES, "xabc", ".*abc")).isEqualTo(true);
            assertThat(call(MATCHES, "12345", "\\d+")).isEqualTo(true);
        }

        @Test
        void aBadArgumentOrInvalidPatternIsFalse() {
            assertThat(call(MATCHES, "abc", "(")).isEqualTo(false);
            assertThat(call(MATCHES, null, "a")).isEqualTo(false);
            assertThat(call(MATCHES, "a", null)).isEqualTo(false);
            assertThat(call(MATCHES, "a", List.of())).isEqualTo(false);
        }

        @Test
        void aPatternLongerThanTheCapIsFalse() {
            String longPattern = "a".repeat(RegexGuard.MAX_PATTERN_LENGTH + 1);

            assertThat(call(MATCHES, "a".repeat(RegexGuard.MAX_PATTERN_LENGTH + 1), longPattern)).isEqualTo(false);
            assertThat(call(MATCHES, "a".repeat(RegexGuard.MAX_PATTERN_LENGTH),
                    "a".repeat(RegexGuard.MAX_PATTERN_LENGTH))).isEqualTo(true); // the cap is inclusive
        }

        @Test
        void aTextLongerThanTheCapIsFalseAndTheCapIsInclusive() {
            String atCap = "a".repeat(RegexGuard.MAX_INPUT_LENGTH);

            assertThat(call(MATCHES, atCap, "a*")).isEqualTo(true);
            assertThat(call(MATCHES, atCap + "a", "a*")).isEqualTo(false);
        }

        /** The regex cannot hang: a catastrophically backtracking pattern on capped input stops at its read budget. */
        @Test
        @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.SECONDS)
        void aCatastrophicPatternStopsAtItsReadBudget() {
            String text = "a".repeat(RegexGuard.MAX_INPUT_LENGTH - 1) + "!";

            assertThat(call(MATCHES, text, "(a+)+b")).isEqualTo(false);
            assertThat(call(MATCHES, "a".repeat(40) + "!", "(a|aa)+$")).isEqualTo(false);
        }

        @Test
        void theCompiledPatternIsReusedAndAnInvalidOneIsRemembered() {
            assertThatCode(() -> {
                for (int i = 0; i < 1000; i++) {
                    call(MATCHES, "abc", "a.c");
                    call(MATCHES, "abc", "(");
                }
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    class Numbers {

        @Test
        void isNumberAcceptsNumbersAndNumericTextOnly() {
            assertThat(call(IS_NUMBER, 3d)).isEqualTo(true);
            assertThat(call(IS_NUMBER, "12.5")).isEqualTo(true);
            assertThat(call(IS_NUMBER, " -3e2 ")).isEqualTo(true);
            assertThat(call(IS_NUMBER, ".5")).isEqualTo(true);
            assertThat(call(IS_NUMBER, "abc")).isEqualTo(false);
            assertThat(call(IS_NUMBER, "")).isEqualTo(false);
            assertThat(call(IS_NUMBER, "NaN")).isEqualTo(false);
            assertThat(call(IS_NUMBER, Double.POSITIVE_INFINITY)).isEqualTo(false);
            assertThat(call(IS_NUMBER, Double.NaN)).isEqualTo(false);
            assertThat(call(IS_NUMBER, true)).isEqualTo(false);
            assertThat(call(IS_NUMBER, (Object) null)).isEqualTo(false);
        }

        @Test
        void numberConvertsTextAndIsNullOtherwise() {
            assertThat(call(NUMBER, "12.5")).isEqualTo(12.5);
            assertThat(call(NUMBER, " 7 ")).isEqualTo(7d);
            assertThat(call(NUMBER, 4d)).isEqualTo(4d);
            assertThat(call(NUMBER, "x")).isNull();
            assertThat(call(NUMBER, true)).isNull();
            assertThat(call(NUMBER, "1e400")).isNull(); // infinite
            assertThat(call(NUMBER, Double.NaN)).isNull();
            assertThat(call(NUMBER, (Object) null)).isNull();
        }

        @Test
        void roundHalvesAwayFromZeroAndTakesOptionalPlaces() {
            assertThat(call(ROUND, 2.5)).isEqualTo(3d);
            assertThat(call(ROUND, -2.5)).isEqualTo(-3d);
            assertThat(call(ROUND, 2.4)).isEqualTo(2d);
            assertThat(call(ROUND, 2.345, 2d)).isEqualTo(2.35);
            assertThat(call(ROUND, "7.6")).isEqualTo(8d);
        }

        @Test
        void roundIsNullForBadPlacesOrNonNumbers() {
            assertThat(call(ROUND, 1d, 16d)).isNull();
            assertThat(call(ROUND, 1d, -1d)).isNull();
            assertThat(call(ROUND, 1d, 1.5)).isNull();
            assertThat(call(ROUND, 1d, "x")).isNull();
            assertThat(call(ROUND, 1d, (Object) null)).isNull();
            assertThat(call(ROUND, "x")).isNull();
            assertThat(call(ROUND, (Object) null)).isNull();
        }

        @Test
        void floorCeilAndAbsWorkOnNumbers() {
            assertThat(call(FLOOR, -1.5)).isEqualTo(-2d);
            assertThat(call(CEIL, 1.2)).isEqualTo(2d);
            assertThat(call(ABS, -3d)).isEqualTo(3d);
            assertThat(call(ABS, "-4.5")).isEqualTo(4.5);
            assertThat(call(FLOOR, "x")).isNull();
            assertThat(call(CEIL, true)).isNull();
            assertThat(call(ABS, (Object) null)).isNull();
        }

        @Test
        void minAndMaxTakeAnyNumberOfArguments() {
            assertThat(call(MIN, 3d, "1", 2d)).isEqualTo(1d);
            assertThat(call(MAX, 3d, "1", 2d)).isEqualTo(3d);
            assertThat(call(MIN, 5d)).isEqualTo(5d);
            assertThat(call(MAX, -1d, -7d)).isEqualTo(-1d);
            assertThat(call(MIN, 3d, (Object) null)).isNull();
            assertThat(call(MAX, 3d, "x")).isNull();
            assertThat(call(MAX, 3d, true)).isNull();
        }

        @Test
        @Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
        void randomIsWithinItsBoundsAndSwapsReversedOnes() {
            List<Double> wholes = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                double value = (Double) call(RANDOM, 1d, 6d);
                assertThat(value).isBetween(1d, 6d);
                assertThat(value).isEqualTo(Math.rint(value));
                wholes.add(value);
            }
            assertThat(wholes.stream().collect(Collectors.toSet())).containsAll(Set.of(1d, 6d));
            assertThat((Double) call(RANDOM, 5d, 1d)).isBetween(1d, 5d);
            assertThat(call(RANDOM, 2.5, 2.5)).isEqualTo(2.5);
            for (int i = 0; i < 100; i++) {
                assertThat((Double) call(RANDOM, 0d, 1.0001)).isBetween(0d, 1.0001);
            }
        }

        @Test
        void randomIsNullForNonNumbers() {
            assertThat(call(RANDOM, "x", 2d)).isNull();
            assertThat(call(RANDOM, 1d, (Object) null)).isNull();
        }

        @Test
        void formatNumberGroupsAndRoundsInTheRootLocale() {
            assertThat(call(FORMAT_NUMBER, 1234.5)).isEqualTo("1,235");
            assertThat(call(FORMAT_NUMBER, 1234.5, 2d)).isEqualTo("1,234.50");
            assertThat(call(FORMAT_NUMBER, -1234567d, 0d)).isEqualTo("-1,234,567");
            assertThat(call(FORMAT_NUMBER, "1234", 1d)).isEqualTo("1,234.0");
            assertThat(call(FORMAT_NUMBER, -0.0, 2d)).isEqualTo("0.00");
            assertThat(call(FORMAT_NUMBER, 0.5)).isEqualTo("1");
        }

        @Test
        void formatNumberIsNullForBadArguments() {
            assertThat(call(FORMAT_NUMBER, 1d, 11d)).isNull();
            assertThat(call(FORMAT_NUMBER, 1d, -1d)).isNull();
            assertThat(call(FORMAT_NUMBER, "x")).isNull();
            assertThat(call(FORMAT_NUMBER, true)).isNull();
        }
    }

    @Nested
    class Players {

        private static final UUID ALEX = UUID.fromString("0f3c1a2e-1111-4222-8333-444455556666");

        private static Player player(String name, UUID id) {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getUniqueId" -> id;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Test
        void isPlayerIsTrueOnlyForTheRunsOwnPlayerByNameOrUuid() {
            LogicContext run = new MapLogicContext(Map.of()).with(Facets.PLAYER, player("Alex", ALEX));

            assertThat(IS_PLAYER.call(List.of("Alex"), run)).isEqualTo(true);
            assertThat(IS_PLAYER.call(List.of("alex"), run)).isEqualTo(true);
            assertThat(IS_PLAYER.call(List.of(ALEX.toString()), run)).isEqualTo(true);
            assertThat(IS_PLAYER.call(List.of("Bob"), run)).isEqualTo(false);
            assertThat(IS_PLAYER.call(List.of(" Alex "), run)).isEqualTo(true);
        }

        @Test
        void isPlayerIsFalseWithoutAPlayerOrForANonTextArgument() {
            LogicContext noPlayer = new MapLogicContext(Map.of());
            LogicContext run = new MapLogicContext(Map.of()).with(Facets.PLAYER, player("Alex", ALEX));

            assertThat(IS_PLAYER.call(List.of("Alex"), noPlayer)).isEqualTo(false);
            assertThat(IS_PLAYER.call(Arrays.asList(new Object[] {null}), run)).isEqualTo(false);
            assertThat(IS_PLAYER.call(List.of(1d), run)).isEqualTo(false);
        }
    }

    @Test
    void everyBuiltInCallsEitherReturnsOrAnswersAsDataForWrongTypes() {
        List<ExpressionFunction> all = BuiltinLogic.functions();
        Object[] wrong = {null, true, List.of(), Map.of("k", 1d), "not a number", -0.0};

        for (ExpressionFunction function : all) {
            for (Object value : wrong) {
                assertThatCode(() -> function.call(Arrays.asList(value, value), EMPTY))
                        .as(function.id() + " with " + value)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void theBuiltInSetIsTheTwentyTheDesignNames() {
        assertThat(BuiltinLogic.functions().stream().map(ExpressionFunction::id).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "contains", "equals_ignore_case", "starts_with", "ends_with", "matches", "length",
                        "lower", "upper", "trim", "is_number", "is_player", "number", "round", "floor", "ceil",
                        "min", "max", "abs", "random", "format_number");
    }
}
