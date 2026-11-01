package dev.materione.behavior.logic;

import dev.materione.api.logic.ExpressionCompiler;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.ValueExpression;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.expr.Expression;
import dev.materione.behavior.expr.ExpressionException;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.behavior.expr.ResumableExpressions;
import dev.materione.behavior.interp.Template;
import java.util.concurrent.CompletionStage;

/**
 * The {@link ExpressionCompiler} the engine hands to {@code ParamReader}: templates and bare
 * expressions compiled by the one expression engine (runtime-systems §2.1). Parsing happens here,
 * at rule load, and binds the calls it finds against its {@link FunctionResolver}; rendering and
 * evaluation are tree walks against the run's context.
 */
public final class EngineExpressions implements ExpressionCompiler {

    /** No function calls: a compiler for a caller with no registry. Calls in its input are unknown-function errors. */
    public static final EngineExpressions INSTANCE = new EngineExpressions(FunctionResolver.NONE);

    private final FunctionResolver functions;

    private EngineExpressions(FunctionResolver functions) {
        this.functions = functions;
    }

    /** A compiler whose calls resolve against {@code functions}; the rule parser passes the live registry. */
    public static EngineExpressions with(FunctionResolver functions) {
        return new EngineExpressions(functions == null ? FunctionResolver.NONE : functions);
    }

    @Override
    public TemplateText template(String source) {
        // throws IllegalArgumentException on a malformed ${...} or an unknown function
        return new EngineTemplate(source, Template.compile(source, functions));
    }

    @Override
    public ValueExpression expression(String source) {
        try {
            return new EngineExpression(ExpressionParser.parse(source, functions));
        } catch (ExpressionException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * The expression engine's view of a run's context, with the run attached for the functions. A
     * context that already holds one (the engine's own) hands it over without allocating.
     */
    public static EvaluationContext evaluationOf(LogicContext context) {
        return context instanceof EvaluationAware aware
                ? aware.evaluation()
                : EvaluationContext.of(context::resolve).withLogic(context);
    }

    private record EngineTemplate(String source, Template.Compiled compiled) implements TemplateText {
        @Override
        public String render(LogicContext context) {
            // ${...} first, then %placeholders%: the order the M2 executors used
            return context.applyPlaceholders(compiled.render(evaluationOf(context)));
        }

        @Override
        public CompletionStage<String> renderResumable(LogicContext context, ReadExecutor executor) {
            return compiled.renderResumable(context, executor).thenApply(text -> {
                if (!executor.active()) {
                    throw new java.util.concurrent.CancellationException("run ended");
                }
                return context.applyPlaceholders(text);
            });
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

        @Override
        public CompletionStage<Object> evaluateResumable(LogicContext context, ReadExecutor executor) {
            return ResumableExpressions.evaluate(expression.tree(), ResumableExpressions.reads(context, executor));
        }
    }
}
