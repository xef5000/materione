package dev.materione.behavior.rule;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import java.util.Objects;

/**
 * One action step after typed parse: the registered {@link ActionType}, the immutable parameter
 * object it produced at load, and the two universal properties every action carries
 * ({@code chance:} 0..1, {@code delay:} ticks, the shape Nexo's action DSL proved out).
 *
 * <p><b>Seam for the flow interpreter.</b> This is the M2 engine's step, kept so rules behave
 * exactly as before while their actions became typed. The flow compiler (logic.md §5.1, §13 L2)
 * replaces it with {@code ActionStep} in a sealed step tree and deletes this class; nothing
 * outside {@link Rule}, {@link RuleParser} and {@code RuleEngine} refers to it.
 *
 * @param id the action key as the author wrote it (diagnostics and trace)
 * @param type the resolved action type
 * @param params the object {@code type.parse} produced
 * @param chance probability from 0 to 1 that the step runs
 * @param delayTicks ticks to wait before the step runs
 * @param <P> the type's parameter object
 */
public record BoundAction<P>(String id, ActionType<P> type, P params, double chance, long delayTicks) {

    /** Key of the universal chance property on an action map. */
    public static final String CHANCE_KEY = "chance";

    /** Key of the universal delay property on an action map. */
    public static final String DELAY_KEY = "delay";

    public BoundAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    /**
     * Parses {@code reader} with {@code type} and binds the result.
     *
     * @throws IllegalArgumentException when the type rejects the parameters
     */
    public static <P> BoundAction<P> bind(
            String id, ActionType<P> type, ParamReader reader, double chance, long delayTicks) {
        P params = type.parse(reader);
        if (params == null) {
            throw new IllegalArgumentException("the action produced no parameters");
        }
        return new BoundAction<>(id, type, params, chance, delayTicks);
    }

    /** The thread the step must run on. */
    public Threading threading() {
        return type.threading();
    }

    /** Runs the step against {@code context}. */
    public ActionOutcome execute(LogicContext context) {
        return type.execute(params, context);
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
