package dev.materione.behavior.flow;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Quantity;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetSpec;
import dev.materione.api.logic.Threading;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * One action after typed parse: the registered {@link ActionType}, the immutable parameter object
 * it produced at load, and the two universal properties every action carries ({@code chance:} 0..1,
 * {@code delay:} ticks, the shape Nexo's action DSL proved out). Both take a number or a template
 * that renders to one (logic.md section 42, #470): a literal is checked at load, a template when the step runs.
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
 * @param target who or what the action acts on, parsed once at load (logic.md §39); {@code null} when the step wrote
 *        none or wrote {@code self}, which runs the action as it always did, with no resolution step at all
 * @param <P> the type's parameter object
 */
public record ActionStep<P>(StepPath path, String id, ActionType<P> type, P params, Quantity chance,
        Quantity delayTicks, StepTarget target) implements Step {

    /** Key of the universal chance property on an action map. */
    public static final String CHANCE_KEY = "chance";

    /** Key of the universal delay property on an action map. */
    public static final String DELAY_KEY = "delay";

    /** Both timing properties of one run, each resolved to a literal (a template rendered on its own thread). */
    public record Timing(Quantity chance, Quantity delayTicks) {}

    public ActionStep {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(chance, "chance");
        Objects.requireNonNull(delayTicks, "delayTicks");
    }

    /** A step with no target: the action acts on the run's own player. */
    public ActionStep(StepPath path, String id, ActionType<P> type, P params, double chance, long delayTicks) {
        this(path, id, type, params, literalChance(chance), literalDelay(delayTicks), null);
    }

    /**
     * Parses {@code reader} with {@code type} and binds the result.
     *
     * @throws IllegalArgumentException when the type rejects the parameters
     */
    public static <P> ActionStep<P> bind(
            StepPath path, String id, ActionType<P> type, ParamReader reader, Quantity chance, Quantity delayTicks) {
        P params = type.parse(reader);
        if (params == null) {
            throw new IllegalArgumentException("the action produced no parameters");
        }
        return new ActionStep<>(path, id, type, params, chance, delayTicks, null);
    }

    /** The chance a literal is: a probability from 0 to 1 (checked at load by the compiler). */
    static Quantity literalChance(double chance) {
        return Quantity.constant(chance, 0, 1, false);
    }

    /** The delay a literal is: a whole number of ticks from 0 to {@link WaitStep#MAX_TICKS}. */
    static Quantity literalDelay(long ticks) {
        return Quantity.constant(ticks, 0, WaitStep.MAX_TICKS, true);
    }

    /** True when the chance or the delay is a template, so the run must render it before it can roll or park. */
    public boolean timingIsTemplate() {
        return chance.isTemplate() || delayTicks.isTemplate();
    }

    /**
     * Renders the chance and the delay for one run: each template on the thread its own reads need (logic.md
     * section 42, #470). A literal resolves at once.
     */
    public CompletionStage<Timing> resolveTiming(LogicContext context, ReadExecutor reads) {
        return chance.resolveResumable(context, reads).thenCompose(resolvedChance ->
                delayTicks.resolveResumable(context, reads).thenApply(resolvedDelay ->
                        new Timing(resolvedChance, resolvedDelay)));
    }

    /**
     * This step acting on {@code spec}, which the action accepts the kinds {@code admits} of. {@code self} (and an
     * absent spec) is no target at all: the step keeps no resolution to run.
     */
    public ActionStep<P> withTarget(TargetSpec spec, Set<TargetKind> admits) {
        StepTarget resolved = spec == null || spec instanceof TargetSpec.Self ? null : new StepTarget(spec, admits);
        return new ActionStep<>(path, id, type, params, chance, delayTicks, resolved);
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
        if (chance.isTemplate() || chance.literal() < 1) {
            out.append(" chance=").append(chance.isTemplate() ? chance.template().source() : chance.literal());
        }
        if (delayTicks.isTemplate() || delayTicks.literal() > 0) {
            out.append(" delay=").append(delayTicks.isTemplate() ? delayTicks.template().source() : delayTicks.literal());
        }
        if (target != null) {
            out.append(" target=").append(target.spec().describe());
        }
        return out.toString();
    }
}
