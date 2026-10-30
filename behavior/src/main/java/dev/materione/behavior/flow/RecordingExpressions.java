package dev.materione.behavior.flow;

import dev.materione.api.logic.ExpressionCompiler;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.ValueExpression;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.behavior.logic.EngineExpressions;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's {@link ExpressionCompiler} with a notebook: it remembers every template and
 * expression an action or condition type compiled while parsing its parameters. A type's parameter
 * object is opaque to the compiler, so this is how the {@code ${root}} check (logic.md §2.6) sees
 * the variable paths an author wrote without any type having to cooperate.
 */
final class RecordingExpressions implements ExpressionCompiler {

    private final EngineExpressions engine;
    private final List<String> templates = new ArrayList<>();
    private final List<String> expressions = new ArrayList<>();

    /** @param functions the registry calls bind against, the same one the rule's own expressions use */
    RecordingExpressions(FunctionResolver functions) {
        this.engine = EngineExpressions.with(functions);
    }

    @Override
    public TemplateText template(String source) {
        TemplateText compiled = engine.template(source);
        templates.add(source);
        return compiled;
    }

    @Override
    public ValueExpression expression(String source) {
        ValueExpression compiled = engine.expression(source);
        expressions.add(source);
        return compiled;
    }

    /** Checks everything recorded so far against {@code unit}'s known roots. */
    void check(CompileUnit unit, StepPath path) {
        for (String source : templates) {
            unit.checkTemplate(source, path);
        }
        for (String source : expressions) {
            unit.checkExpressionSource(source, path);
        }
    }
}
