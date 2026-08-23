package dev.materione.behavior.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The universal expression language's contract (runtime-systems §2.1). */
class ExpressionParserTest {

    private Object eval(String source, Map<String, Object> root) {
        return ExpressionParser.parse(source).eval(new EvaluationContext(root));
    }

    private Object eval(String source) {
        return eval(source, Map.of());
    }

    // --- literals & paths -----------------------------------------------------

    @Test
    void evaluatesLiterals() {
        assertThat(eval("42")).isEqualTo(42.0);
        assertThat(eval("1.5")).isEqualTo(1.5);
        assertThat(eval("-3")).isEqualTo(-3.0);
        assertThat(eval("'hi'")).isEqualTo("hi");
        assertThat(eval("\"double\"")).isEqualTo("double");
        assertThat(eval("true")).isEqualTo(true);
        assertThat(eval("false")).isEqualTo(false);
        assertThat(eval("null")).isNull();
    }

    @Test
    void resolvesDottedPathsThroughNestedMaps() {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.<String, Object>of(
                "name", "Steve", "health", 12.5, "tags", Map.<String, Object>of("vip", true)));
        assertThat(eval("player.name", root)).isEqualTo("Steve");
        assertThat(eval("player.health", root)).isEqualTo(12.5);
        assertThat(eval("player.tags.vip", root)).isEqualTo(true);
        // missing segments are data, not errors
        assertThat(eval("player.missing", root)).isNull();
        assertThat(eval("player.health.deeper", root)).isNull();
        assertThat(eval("nothing.here", root)).isNull();
    }

    // --- arithmetic -----------------------------------------------------------

    @Test
    void evaluatesArithmeticWithPrecedence() {
        assertThat(eval("2 + 3 * 4")).isEqualTo(14.0);
        assertThat(eval("(2 + 3) * 4")).isEqualTo(20.0);
        assertThat(eval("10 / 4")).isEqualTo(2.5);
        assertThat(eval("10 % 4")).isEqualTo(2.0);
        assertThat(eval("2 - 3 - 4")).isEqualTo(-5.0); // left associative
    }

    @Test
    void divisionByZeroIsNullNotACrash() {
        assertThat(eval("1 / 0")).isNull();
        assertThat(eval("1 % 0")).isNull();
    }

    @Test
    void plusConcatenatesWhenEitherSideIsAString() {
        assertThat(eval("'a' + 'b'")).isEqualTo("ab");
        assertThat(eval("'hp: ' + player.health", Map.of("player", Map.of("health", 20)))).isEqualTo("hp: 20");
        assertThat(eval("1 + 'x'")).isEqualTo("1x");
    }

    @Test
    void arithmeticOnNonNumbersThrowsEvalException() {
        assertThatThrownBy(() -> eval("player.name * 2", Map.of("player", Map.of("name", "x"))))
                .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> eval("null + 1")).isInstanceOf(ExpressionException.class);
    }

    // --- comparison & logic ------------------------------------------------------

    @Test
    void comparesNumbersAndStrings() {
        assertThat(eval("1 < 2")).isEqualTo(true);
        assertThat(eval("2 <= 2")).isEqualTo(true);
        assertThat(eval("3 > 4")).isEqualTo(false);
        assertThat(eval("'b' >= 'a'")).isEqualTo(true);
        assertThat(eval("'apple' == 'apple'")).isEqualTo(true);
        assertThat(eval("'a' != 'b'")).isEqualTo(true);
    }

    @Test
    void equalityTreatsMissingAndNullTheSame() {
        assertThat(eval("player.x == null", Map.of())).isEqualTo(true);
        assertThat(eval("player.x != 'y'", Map.of())).isEqualTo(true);
        assertThat(eval("1 == 1.0")).isEqualTo(true); // numeric cross-type equality
    }

    @Test
    void logicalOperatorsShortCircuitAndCoerce() {
        var boom = Map.<String, Object>of("side", "should not evaluate");
        // right side would throw if evaluated (string * number)
        assertThat(eval("false && side * 2", boom)).isEqualTo(false);
        assertThat(eval("true || side * 2", boom)).isEqualTo(true);
        assertThat(eval("!false")).isEqualTo(true);
        assertThat(eval("!0")).isEqualTo(true);
        // documented truthiness: null/false/"false"/empty → false; non-zero numbers → true
        assertThat(eval("player.x ? 'y' : 'n'", Map.of())).isEqualTo("n");
        assertThat(eval("'false' ? 'y' : 'n'")).isEqualTo("n");
        assertThat(eval("'' ? 'y' : 'n'")).isEqualTo("n");
        assertThat(eval("0 ? 'y' : 'n'")).isEqualTo("n");
        assertThat(eval("1 ? 'y' : 'n'")).isEqualTo("y");
    }

    @Test
    void ternaryPicksLazily() {
        assertThat(eval("player.vip ? 2 : 1", Map.of("player", Map.of("vip", true)))).isEqualTo(2.0);
        assertThat(eval("'a' == 'b' ? side * 2 : 'safe'",
                Map.of("side", "explosive"))).isEqualTo("safe");
    }

    // --- parse errors -------------------------------------------------------------

    @Test
    void rejectsBrokenSyntaxAtLoad() {
        assertThatThrownBy(() -> ExpressionParser.parse("1 +")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("(1")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("'unterminated")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("a.")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("1 2")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("@")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("   ")).isInstanceOf(ExpressionException.class);
    }
}
