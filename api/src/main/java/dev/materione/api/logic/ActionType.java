package dev.materione.api.logic;

import java.util.concurrent.CompletionStage;

/**
 * One action in the {@code actions} registry (logic.md §4.1): the verb a rule step runs
 * ({@code run_command}, {@code message}, later {@code take_money}, {@code gui.open}). Built-ins and
 * addons implement this one interface and register through
 * {@link LogicApi#actions()}; the engine, the editor palette and the docs read the same registry.
 *
 * <p>An action is a factory plus a behaviour, not a step: {@link #parse} runs once per authored
 * step at load and produces the immutable {@code P}; {@link #execute} runs that {@code P} every
 * time the step is reached. Implementations are stateless (one instance serves every step and
 * every player concurrently), so anything per-run lives in {@code P} or the {@link LogicContext}.
 *
 * <h2>Contracts</h2>
 *
 * <ul>
 *   <li><b>parse</b> is pure and total over bad input: it touches no server state and throws
 *       {@link IllegalArgumentException} (never returns null, never throws anything else for
 *       content) to reject a step. The engine diagnoses and skips that step and keeps its
 *       siblings. It may run off the main thread.
 *   <li><b>execute</b> runs on the thread named by {@link #threading()}. It reports
 *       {@link ActionOutcome#OK} or {@link ActionOutcome#failed}; an exception is contained and
 *       counted as a failure, so a throwing action never stops the chain or the server. It must
 *       not retain {@code context}, and must not block.
 *   <li><b>describe</b> is read for the vocabulary on demand; it must be cheap and return an
 *       equal value each time. Its parameter schema is the schema {@code parse} reads.
 *   <li><b>id</b> is the local name an author writes ({@code take_money}, {@code gui.open}). It
 *       must equal the {@code value} of the {@code NamespacedId} the type is registered under;
 *       Materione's own types register under {@code materione:}, which authors may omit, while
 *       addon types are written qualified ({@code example:greet}).
 * </ul>
 *
 * @param <P> the immutable parsed parameters of one step
 */
public interface ActionType<P> {

    /** The local action name as written in {@code do:} ({@code run_command}). */
    String id();

    /** Editor/doc metadata, palette category and scalar shorthand. */
    ActionDescription describe();

    /**
     * The thread {@link #execute} must run on. Declared per type; the engine dispatches (logic.md
     * §4.1). A type whose placement depends on its parameters declares the stricter choice.
     */
    Threading threading();

    /**
     * The thread a step with these parsed parameters must run on. It defaults to the type's
     * {@link #threading()}; a type whose placement depends on its parameters overrides it
     * ({@code run_command} runs on the global thread for the console and in the player's region
     * for {@code as: player|op}). The engine calls this overload, never the bare one, when it
     * dispatches a step; the bare one stays the type's headline value for the vocabulary.
     */
    default Threading threading(P params) {
        return threading();
    }

    /**
     * Turns one step's authored parameters into the immutable object {@link #execute} runs.
     *
     * @throws IllegalArgumentException to reject the step, with a message an author can act on
     */
    P parse(ParamReader params);

    /**
     * Runs the step. {@code params} is the object {@link #parse} produced for this step.
     *
     * @return {@link ActionOutcome#OK}, or {@link ActionOutcome#failed} with a short reason
     */
    ActionOutcome execute(P params, LogicContext context);

    /**
     * Optional resumable execution. The default runs the legacy callback exactly once on its
     * declared owner; it cannot suspend within that callback. Types that evaluate templates or
     * expressions around side effects may override this and chain their operations explicitly.
     */
    default CompletionStage<ActionOutcome> executeResumable(P params, LogicContext context, ReadExecutor executor) {
        return executor.on(threading(params), () -> execute(params, context));
    }

    /**
     * Whether the server can run this action now (a provider may be missing). Read each time the
     * vocabulary is built. An unavailable action still parses and loads; running it fails with the
     * reason (logic.md §4.5).
     */
    default Availability availability() {
        return Availability.AVAILABLE;
    }
}
