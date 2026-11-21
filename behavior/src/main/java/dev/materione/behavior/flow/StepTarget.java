package dev.materione.behavior.flow;

import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetSpec;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The target of one action step, parsed at load (logic.md §39): what it names and what the action accepts of it.
 * The engine resolves it every time the step is reached, through the platform's resolver, and runs the action on
 * each subject it finds.
 *
 * @param spec the parsed {@code target:}; never {@link TargetSpec.Self}
 * @param admits the kinds the action accepts: its declared kinds, plus players when it declares entities
 */
public record StepTarget(TargetSpec spec, Set<TargetKind> admits) {

    public StepTarget {
        Objects.requireNonNull(spec, "spec");
        var kinds = EnumSet.noneOf(TargetKind.class);
        kinds.addAll(admits);
        admits = Set.copyOf(kinds);
    }
}
