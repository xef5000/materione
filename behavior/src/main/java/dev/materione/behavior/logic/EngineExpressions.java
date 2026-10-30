package dev.materione.behavior.logic;

import dev.materione.api.logic.ExpressionCompiler;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.ValueExpression;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.expr.Expression;
import dev.materione.behavior.expr.ExpressionException;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.interp.Template;

/**
 * The {@link ExpressionCompiler} the engine hands to {@code ParamReader}: templates and bare
 * expressions compiled by the one expression engine (runtime-systems §2.1). Parsing happens here,
 * at rule load; rendering and evaluation are tree walks against the run's context.
 */
public final class EngineExpressions implements ExpressionCompiler {

    /** Stateless; the template cache is process-wide and keyed by source text. */
    public static final EngineExpressions INSTANCE = new EngineExpressions();

    private EngineExpressions() {}

    @Override
    public TemplateText template(String source) {
        Template.validate(source); // throws IllegalArgumentException on a malformed ${...}
        return new EngineTemplate(source);
    }

    @Override
    public ValueExpression expression(String source) {
        try {
            return new EngineExpression(ExpressionParser.parse(source));
        } catch (ExpressionException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** The expression engine's view of a run's context. */
    static EvaluationContext evaluationOf(LogicContext context) {
        return context instanceof MapLogicContext map
                ? map.evaluation()
                : EvaluationContext.of(context::resolve);
    }

    private record EngineTemplate(String source) implements TemplateText {
        @Override
        public String render(LogicContext context) {
            // ${...} first, then %placeholders%: the order the M2 executors used
            return context.applyPlaceholders(Template.render(source, evaluationOf(context)));
        }
    }

    private record EngineExpression(Expression expression) implements ValueExpression {
        @Override
        public String source() {
            return expression.source();
        }

        @Override
        public Object evaluate(LogicContext context) {
            try {
                return expression.eval(evaluationOf(context));
            } catch (ExpressionException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }
}
