package dev.materione.api.logic;

/**
 * A parsed expression whose result is a typed value rather than text ({@code vars.count + 1}).
 * Obtained from {@link ParamReader#expression}; syntax errors surface at that call, at load.
 *
 * <p>The value model is the expression engine's: {@code Double} for every number, {@code Boolean},
 * {@code String} and {@code null}. Evaluation is a pure read of the context and throws
 * {@link IllegalStateException} for an impossible coercion ({@code 'a' * 3}); the engine contains
 * it.
 */
public interface ValueExpression {

    /** The authored expression text. */
    String source();

    /** Evaluates against {@code context}. */
    Object evaluate(LogicContext context);
}
