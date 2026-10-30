package dev.materione.behavior.flow;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import java.util.Objects;

/**
 * One action after typed parse: the registered {@link ActionType}, the immutable parameter object
 * it produced at load, and the two universal properties every action carries ({@code chance:} 0..1,
 * {@code delay:} ticks, the shape Nexo's action DSL proved out).
 *
 * <p>A failure never aborts the flow on its own; only a surrounding {@link RequireStep} reacts to
 * one. A positive {@code delayTicks} parks the run before the action (§5.2), exactly like a
 * {@link WaitStep} placed in front of it.
 *
 * @param path where the step was written
 * @param id the action key as the author wrote it (diagnostics and trace)
 * @param type the resolved action type
 * @param params the object {@code type.parse} produced
 * @param chance probability from 0 to 1 that the step runs
 * @param delayTicks ticks to wait before the step runs
 * @param <P> the type's parameter object
 */
public record ActionStep<P>(StepPath path, String id, ActionType<P> type, P params, double chance, long delayTicks)
        implements Step {

    /** Key of the universal chance property on an action map. */
    public static final String CHANCE_KEY = "chance";

    /** Key of the universal delay property on an action map. */
    public static final String DELAY_KEY = "delay";

    public ActionStep {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    /**
     * Parses {@code reader} with {@code type} and binds the result.
     *
     * @throws IllegalArgumentException when the type rejects the parameters
     */
    public static <P> ActionStep<P> bind(
            StepPath path, String id, ActionType<P> type, ParamReader reader, double chance, long delayTicks) {
        P params = type.parse(reader);
        if (params == null) {
            throw new IllegalArgumentException("the action produced no parameters");
        }
        return new ActionStep<>(path, id, type, params, chance, delayTicks);
    }

    /** The thread this step must run on, which may depend on its parameters. */
    public Threading threading() {
        return type.threading(params);
    }

    /** Runs the action against {@code context}. */
    public ActionOutcome execute(LogicContext context) {
        return type.execute(params, context);
    }

    @Override
    public String kind() {
        return "action";
    }

    @Override
    public String toString() {
        var out = new StringBuilder(id).append(params);
        if (chance < 1) {
            out.append(" chance=").append(chance);
        }
        if (delayTicks > 0) {
            out.append(" delay=").append(delayTicks);
        }
        return out.toString();
    }
}
