package dev.materione.api.logic;

import java.util.List;

/**
 * A function callable from an expression (logic.md §4.2): {@code contains(a, b)},
 * {@code lower(s)}, {@code random(n)}. Functions are a registry like actions and conditions, so an
 * addon adds one without touching the engine.
 *
 * <p>This is the SPI and its registry. Binding calls into the expression grammar, arity checking
 * and the built-in function set belong to the expression work (logic.md §13 L3); until it lands no
 * expression calls a registered function, but the registry and the vocabulary already carry it.
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
}
