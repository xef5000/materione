package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Templates and expressions compile at load and run against a context's variable tree. */
class EngineExpressionsTest {

    private static MapLogicContext context() {
        return new MapLogicContext(Map.of(
                "player", Map.of("name", "Alex", "level", 12d),
                "item", Map.of("amount", 3d)));
    }

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    @Test
    void aTemplateRendersExpressionsThenPlaceholders() {
        var template = reader(Map.of("text", "Hi ${player.name}, %server_name% x${item.amount}")).template("text");
        LogicContext ctx = context().withPlaceholders(text -> text.replace("%server_name%", "Lobby"));

        assertThat(template.source()).isEqualTo("Hi ${player.name}, %server_name% x${item.amount}");
        assertThat(template.render(ctx)).isEqualTo("Hi Alex, Lobby x3");
    }

    @Test
    void aMalformedTemplateIsRejectedWhenItIsCompiledNotWhenItRuns() {
        assertThatThrownBy(() -> reader(Map.of("text", "a ${} b")).template("text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text:");
        assertThatThrownBy(() -> reader(Map.of("text", "${1 +}")).template("text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBareExpressionEvaluatesToATypedValue() {
        var expression = reader(Map.of("value", "player.level + 1")).expression("value");

        assertThat(expression.source()).isEqualTo("player.level + 1");
        assertThat(expression.evaluate(context())).isEqualTo(13d);
        assertThatThrownBy(() -> reader(Map.of("value", "1 +")).expression("value"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader(Map.of("value", "'a' * 3")).expression("value").evaluate(context()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anyLogicContextWorksNotJustTheMapOne() {
        LogicContext bare = new LogicContext() {
            @Override
            public <T> java.util.Optional<T> facet(dev.materione.api.logic.ContextFacet<T> facet) {
                return java.util.Optional.empty();
            }

            @Override
            public Object resolve(List<String> path) {
                return path.equals(List.of("answer")) ? 42d : null;
            }

            @Override
            public Map<String, Object> locals() {
                return Map.of();
            }
        };

        assertThat(reader(Map.of("text", "n=${answer}")).template("text").render(bare)).isEqualTo("n=42");
        assertThat(bare.value("answer")).isEqualTo(42d);
    }
}
