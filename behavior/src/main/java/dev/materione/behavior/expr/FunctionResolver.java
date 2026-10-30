package dev.materione.behavior.expr;

import dev.materione.api.logic.ExpressionFunction;
import java.util.Optional;

/**
 * The parse-time view of the {@code expression-functions} registry (logic.md §4.2). The parser asks
 * it for the function a call names and binds the answer into the tree, so a call is resolved once,
 * at rule load, and the evaluator never looks a name up again.
 *
 * <p>The expression package stays free of the registries: the engine passes a resolver built over
 * the live registry, and a caller with no registry passes {@link #NONE}, under which every call is
 * an unknown function. The name is the spelling the author wrote: {@code contains} for a
 * Materione built-in, {@code example.greet} for an addon function (the grammar has no colon, so an
 * addon's qualified id is written with a dot and the parser passes it on as {@code example:greet}).
 */
@FunctionalInterface
public interface FunctionResolver {

    /** No functions at all: every call is a parse error. */
    FunctionResolver NONE = name -> Optional.empty();

    /** The function the authored {@code name} resolves to, or empty when no such function is registered. */
    Optional<ExpressionFunction> resolve(String name);
}
