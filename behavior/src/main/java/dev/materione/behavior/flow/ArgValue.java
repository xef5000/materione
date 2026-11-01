package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.ValueExpression;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import java.util.concurrent.CompletionStage;

/**
 * One value of a {@code call}'s {@code with:} map, compiled (logic.md §2.4, §2.5). Every value is
 * a template: a plain scalar passes through untouched, a string with {@code ${...}} or
 * {@code %token%} is evaluated in the caller's scope when the call is reached, and a string that is
 * exactly one {@code ${expression}} keeps the expression's own type, so a number stays a number
 * ({@code price: "${args.price * 2}"} is still arithmetic-ready in the callee).
 */
public sealed interface ArgValue permits ArgValue.Literal, ArgValue.Text, ArgValue.Typed {

    /** The value for the callee's {@code args.<name>}. */
    Object resolve(LogicContext context);

    /** Resolves once with owner-aware continuation support. */
    default CompletionStage<Object> resolveResumable(LogicContext context, ReadExecutor executor) {
        return executor.on(Threading.ANY, () -> resolve(context));
    }

    /** A value with nothing to evaluate: a number, a boolean, a plain string, a list or a map. */
    record Literal(Object value) implements ArgValue {
        @Override
        public Object resolve(LogicContext context) {
            return value;
        }
    }

    /** A string template, rendered to text. */
    record Text(TemplateText template) implements ArgValue {
        @Override
        public Object resolve(LogicContext context) {
            return template.render(context);
        }

        @Override
        public CompletionStage<Object> resolveResumable(LogicContext context, ReadExecutor executor) {
            return template.renderResumable(context, executor).thenApply(value -> value);
        }
    }

    /** A single {@code ${expression}}, evaluated to its typed result. */
    record Typed(ValueExpression expression) implements ArgValue {
        @Override
        public Object resolve(LogicContext context) {
            return expression.evaluate(context);
        }

        @Override
        public CompletionStage<Object> resolveResumable(LogicContext context, ReadExecutor executor) {
            return expression.evaluateResumable(context, executor);
        }
    }
}
