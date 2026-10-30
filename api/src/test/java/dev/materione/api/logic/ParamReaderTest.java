package dev.materione.api.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Typed, strict, non-coercing reads with defaults: the whole contract of "parse once, run many". */
class ParamReaderTest {

    private enum Mode { FAST, SLOW }

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map);
    }

    @Test
    void textIsRequiredNonBlankAndNeverCoerced() {
        assertThat(reader("command", "say hi").text("command")).isEqualTo("say hi");
        assertThat(reader().text("command", "fallback")).isEqualTo("fallback");
        assertThat(reader("command", null).text("command", "fallback")).isEqualTo("fallback");
        assertThat(reader().optionalText("command")).isEmpty();

        assertThatThrownBy(() -> reader().text("command")).hasMessage("command is required");
        assertThatThrownBy(() -> reader("command", " ").text("command"))
                .hasMessage("command must be a non-blank string");
        assertThatThrownBy(() -> reader("command", 5).text("command"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must be a non-blank string");
    }

    @Test
    void integersAreExactAndRangeChecked() {
        assertThat(reader("n", 7).integer("n", 1, 0, 10)).isEqualTo(7);
        assertThat(reader("n", 7.0).integer("n", 1, 0, 10)).isEqualTo(7);
        assertThat(reader().integer("n", 1, 0, 10)).isEqualTo(1);
        assertThat(reader("n", 3).requiredInteger("n", 0, 10)).isEqualTo(3);

        assertThatThrownBy(() -> reader("n", 7.5).integer("n", 1, 0, 10)).hasMessage("n must be an integer in 0..10");
        assertThatThrownBy(() -> reader("n", 11).integer("n", 1, 0, 10)).hasMessage("n must be an integer in 0..10");
        assertThatThrownBy(() -> reader("n", "7").integer("n", 1, 0, 10)).hasMessage("n must be an integer in 0..10");
        assertThatThrownBy(() -> reader().requiredInteger("n", 0, 10)).hasMessage("n is required");
    }

    @Test
    void decimalsMustBeFiniteAndInRange() {
        assertThat(reader("v", 0.5).decimal("v", 1, 0, 2)).isEqualTo(0.5);
        assertThat(reader().decimal("v", 1, 0, 2)).isEqualTo(1);
        assertThatThrownBy(() -> reader("v", 3).decimal("v", 1, 0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader("v", Double.NaN).decimal("v", 1, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader("v", "loud").decimal("v", 1, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void booleansAreNotTruthyStringsOrNumbers() {
        assertThat(reader("on", true).bool("on", false)).isTrue();
        assertThat(reader().bool("on", true)).isTrue();
        assertThatThrownBy(() -> reader("on", "true").bool("on", false)).hasMessage("on must be true or false");
        assertThatThrownBy(() -> reader("on", 1).bool("on", false)).hasMessage("on must be true or false");
    }

    @Test
    void choicesMatchIgnoringCaseAndReturnTheAllowedSpelling() {
        List<String> allowed = List.of("console", "player", "op");
        assertThat(reader("as", "PLAYER").choice("as", "console", allowed)).isEqualTo("player");
        assertThat(reader().choice("as", "console", allowed)).isEqualTo("console");
        assertThatThrownBy(() -> reader("as", "root").choice("as", "console", allowed))
                .hasMessage("as must be one of console, player, op");
    }

    @Test
    void enumsMatchIgnoringCaseAgainstConstantNames() {
        assertThat(reader("mode", "slow").enumValue("mode", Mode.class, Mode.FAST)).isEqualTo(Mode.SLOW);
        assertThat(reader().enumValue("mode", Mode.class, Mode.FAST)).isEqualTo(Mode.FAST);
        assertThatThrownBy(() -> reader("mode", "warp").enumValue("mode", Mode.class, Mode.FAST))
                .hasMessage("mode must be one of fast, slow");
    }

    @Test
    void templatesCompileThroughTheCompilerAndPrefixItsErrorsWithTheKey() {
        var literal = reader("text", "Hello %player%").template("text");
        assertThat(literal.source()).isEqualTo("Hello %player%");
        assertThat(reader().template("text", "default").source()).isEqualTo("default");
        assertThat(reader().optionalTemplate("text")).isEmpty();
        assertThatThrownBy(() -> reader("text", "Hi ${player.name}").template("text"))
                .hasMessageStartingWith("text: ");
        assertThatThrownBy(() -> reader("text", "1 + 1").expression("text"))
                .hasMessageStartingWith("text: ");
    }

    @Test
    void literalTemplatesRenderThroughThePlaceholderHook() {
        var template = reader("text", "Hi %who%").template("text");
        LogicContext context = new LogicContext() {
            @Override
            public <T> java.util.Optional<T> facet(ContextFacet<T> facet) {
                return java.util.Optional.empty();
            }

            @Override
            public Object resolve(List<String> path) {
                return null;
            }

            @Override
            public Map<String, Object> locals() {
                return Map.of();
            }

            @Override
            public String applyPlaceholders(String text) {
                return text.replace("%who%", "Alex");
            }
        };
        assertThat(template.render(context)).isEqualTo("Hi Alex");
    }

    @Test
    void stringListsAndNestedSectionsAreReadWithTheSameStrictness() {
        var params = reader(
                "tags", List.of("a", "b"),
                "bad", List.of("a", 3),
                "inner", Map.of("n", 2),
                "rows", List.of(Map.of("n", 1), Map.of("n", 2)));

        assertThat(params.strings("tags")).containsExactly("a", "b");
        assertThat(params.strings("absent")).isEmpty();
        assertThatThrownBy(() -> params.strings("bad")).hasMessage("bad must contain non-blank strings");
        assertThat(params.section("inner").requiredInteger("n", 0, 9)).isEqualTo(2);
        assertThat(params.sections("rows")).extracting(row -> row.requiredInteger("n", 0, 9)).containsExactly(1, 2);
        assertThatThrownBy(() -> params.section("absent")).hasMessage("absent is required");
        assertThatThrownBy(() -> params.section("tags")).hasMessage("tags must be a mapping");
    }

    @Test
    void presenceAndRawAccessSeeWhatTheAuthorWrote() {
        var params = reader("a", 1, "b", null);
        assertThat(params.has("a")).isTrue();
        assertThat(params.has("b")).isTrue(); // written, even though null
        assertThat(params.has("c")).isFalse();
        assertThat(params.keys()).containsExactly("a", "b");
        assertThat(params.raw("a")).contains(1);
        assertThat(params.raw("b")).isEmpty();
    }

    @Test
    void valuesAreDeeplyImmutableCopiesAndUnsupportedTypesAreRejected() {
        var source = new java.util.ArrayList<Object>(List.of("x"));
        var params = ParamReader.of(Map.of("list", source));
        source.add("y");
        assertThat(params.strings("list")).containsExactly("x"); // later edits to the YAML tree do not leak in

        assertThatThrownBy(() -> ParamReader.of(Map.of("when", new java.util.Date())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported parameter value");
    }

    @Test
    void theLiteralCompilerRefusesInterpolationSoATestCannotPassSilently() {
        assertThatThrownBy(() -> ExpressionCompiler.LITERAL.template("${x}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionCompiler.LITERAL.expression("1+1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
