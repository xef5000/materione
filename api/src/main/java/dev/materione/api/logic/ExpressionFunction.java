package dev.materione.api.logic;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * A function callable from an expression (logic.md §4.2): {@code contains(a, b)},
 * {@code lower(s)}, {@code random(n)}. Functions are a registry like actions and conditions, so an
 * addon adds one without touching the engine.
 *
 * <p>The expression engine binds a call ({@code name(arguments…)}) against this registry when a
 * rule loads: an unknown name or a wrong argument count is a load error, and the built-in set
 * (logic.md §4.2) is registered through this same SPI.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>Pure and total over its declared argument types.</b> A call has no side effects and,
 *       given equal arguments and context, returns an equal value (a documented exception is
 *       {@code random}). It never blocks.
 *   <li><b>Values</b> are the expression engine's: {@code Double} for every number, {@code
 *       Boolean}, {@code String}, {@code null}. The engine has already checked the arity against
 *       {@link FunctionDescription#minArguments()}/{@link FunctionDescription#maxArguments()}.
 *   <li><b>Failure:</b> an argument of the wrong kind throws {@link IllegalArgumentException}; the
 *       engine contains it as an expression failure (the condition is false, a diagnostic is
 *       rate-limited).
 *   <li><b>id</b> follows {@link ActionType#id()}: the local name an author writes, equal to the
 *       value of the registered id.
 * </ul>
 */
public interface ExpressionFunction {

    /** The local function name as written in expressions ({@code contains}). */
    String id();

    /** Display metadata, the argument schema and the return kind. */
    FunctionDescription describe();

    /**
     * Computes the call.
     *
     * @param arguments the evaluated arguments, in order
     * @param context the run's context, for the rare function that reads a facet ({@code is_player})
     * @throws IllegalArgumentException when an argument has the wrong kind
     */
    Object call(List<Object> arguments, LogicContext context);

    /**
     * Opt-in continuation hook. Existing functions retain their synchronous behaviour; a function
     * whose read needs another owner overrides this and uses {@code executor}. Legacy functions run
     * on the expression's captured original owner, not whichever owner completed their last argument.
     * An unavailable synchronous read completes exceptionally rather than replaying the function.
     */
    default CompletionStage<Object> callResumable(
            List<Object> arguments, LogicContext context, ReadExecutor executor) {
        return executor.onOrigin(() -> call(arguments, context));
    }
}
