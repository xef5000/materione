package dev.materione.behavior.flow;

/**
 * One step of a flow (logic.md §2.2, §5.1). The set is closed: control flow belongs to the engine,
 * and only actions, conditions and triggers are open registries. A small fixed grammar is what makes
 * the node graph (§7.2) total and lossless, and what lets the interpreter be one exhaustive
 * {@code switch}.
 *
 * <p>Every step knows its {@link StepPath}, so a diagnostic, a trace event and a studio node all
 * name the same place in the file.
 */
public sealed interface Step
        permits ActionStep, IfStep, RequireStep, ChooseStep, WaitStep, StopStep, CallStep {

    /** Where the step was written, e.g. {@code behavior.2.do.0}. */
    StepPath path();

    /** The step kind's vocabulary id ({@code action}, {@code if}, {@code require}, ...). */
    String kind();
}
