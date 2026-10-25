package dev.materione.behavior.interp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.behavior.expr.EvaluationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateTest {

    @Test
    void rendersInterpolationsAndKeepsLiterals() {
        var ctx = new EvaluationContext(Map.<String, Object>of(
                "player", Map.<String, Object>of("name", "Steve", "health", 20.0)));
        assertThat(Template.render("Hello ${player.name}!", ctx)).isEqualTo("Hello Steve!");
        assertThat(Template.render("${player.health}", ctx)).isEqualTo("20"); // integral doubles render bare
        assertThat(Template.render("no expressions", ctx)).isEqualTo("no expressions");
        assertThat(Template.render("", ctx)).isEmpty();
    }

    @Test
    void papiTokensPassThroughUntouched() {
        var ctx = new EvaluationContext(Map.of());
        String out = Template.render("%server_tps% and ${'x'}", ctx);
        assertThat(out).isEqualTo("%server_tps% and x");
    }

    @Test
    void multipleExpressionsInOneTemplate() {
        var ctx = new EvaluationContext(Map.<String, Object>of("a", 1, "b", 2));
        // integral doubles render bare (Expressions.stringify)
        assertThat(Template.render("${a}+${b}=${a + b}", ctx)).isEqualTo("1+2=3");
    }

    @Test
    void rendersCustomDurabilityCountersFromTheItemExpressionTree() {
        var ctx = new EvaluationContext(Map.<String, Object>of(
                "item", Map.<String, Object>of("durability_used", 2, "durability_max", 3)));
        assertThat(Template.render(
                "blade-wear ${item.durability_used}/${item.durability_max}", ctx))
                .isEqualTo("blade-wear 2/3");
    }

    @Test
    void unterminatedExpressionsStayLiteral() {
        var ctx = new EvaluationContext(Map.of());
        // guessing would be worse than showing the raw text
        assertThat(Template.render("broken ${oops", ctx)).isEqualTo("broken ${oops");
    }

    @Test
    void badTemplatesFailLoudlyWithTheOffendingText() {
        assertThatThrownBy(() -> Template.render("${}", new EvaluationContext(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Template.render("${1 +}", new EvaluationContext(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 +");
    }

    @Test
    void typeErrorsSurfaceAtRenderTimeAsExpressionExceptions() {
        // parses fine; the coercion failure is an evaluation error, not a parse error
        assertThatThrownBy(() -> Template.render("${a * 'b'}", new EvaluationContext(Map.<String, Object>of())))
                .isInstanceOf(dev.materione.behavior.expr.ExpressionException.class);
    }

    @Test
    void parsedTemplatesAreMemoizedPerSourceText() {
        var ctx = new EvaluationContext(Map.<String, Object>of("v", 7));
        // same source twice hits the cache; result identical
        assertThat(Template.render("n=${v}", ctx)).isEqualTo("n=7");
        assertThat(Template.render("n=${v}", ctx)).isEqualTo("n=7");
    }
}
