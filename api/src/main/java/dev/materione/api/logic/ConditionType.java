package dev.materione.api.logic;

import java.util.concurrent.CompletionStage;

/**
 * One predicate in the {@code conditions} registry (logic.md §4.2): {@code has_permission},
 * {@code has_money}, {@code in_gui}. Registered and described exactly like an {@link ActionType},
 * and parsed once at load into the immutable {@code P}.
 *
 * <h2>Contracts</h2>
 *
 * <ul>
 *   <li><b>parse</b> is pure and rejects with {@link IllegalArgumentException}, exactly as for an
 *       action. The engine skips the step that holds the condition.
 *   <li><b>test</b> is a <em>pure read</em>: it must not mutate the world, the context's
 *       {@linkplain LogicContext#locals() locals}, or its own state, because the engine may
 *       evaluate a condition more than once (the editor's validation, lazy composites) and in any
 *       order. It returns {@code false} rather than throwing when it cannot decide (no player, no
 *       provider); an exception is contained and counts as {@code false}.
 *   <li><b>Threading:</b> a condition runs on the thread of the step that evaluates it, so it may
 *       only read what that thread owns. A condition that needs another region's data is not
 *       expressible; use an action that writes a variable first.
 *   <li><b>id</b> follows the same rule as {@link ActionType#id()}.
 * </ul>
 *
 * @param <P> the immutable parsed parameters of one condition
 */
public interface ConditionType<P> {

    /** The local condition name as written ({@code has_money}). */
    String id();

    /** Editor/doc metadata, palette category and scalar shorthand. */
    ConditionDescription describe();

    /**
     * Turns authored parameters into the immutable object {@link #test} reads.
     *
     * @throws IllegalArgumentException to reject the condition, with a message an author can act on
     */
    P parse(ParamReader params);

    /** Evaluates the condition; a pure read of {@code context} and whatever providers it consults. */
    boolean test(P params, LogicContext context);

    /** Opt-in continuation hook; legacy predicates execute once and cannot be replayed. */
    default CompletionStage<Boolean> testResumable(P params, LogicContext context, ReadExecutor executor) {
        return executor.on(Threading.ANY, () -> test(params, context));
    }

    /** Whether the server can evaluate this condition now; read each time the vocabulary is built. */
    default Availability availability() {
        return Availability.AVAILABLE;
    }
}
