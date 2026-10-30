package dev.materione.behavior.flow;

import java.util.List;

/**
 * A named flow after compilation (logic.md §2.4): the reuse unit a {@code call} step links to.
 * Compiled once per resource; every call site holds a direct reference, so a call at run time is
 * a frame push, not a lookup.
 *
 * @param id the name under {@code flows:}
 * @param path where the flow is declared, {@code flows.<id>}
 * @param steps the flow's steps; their paths sit under {@code flows.<id>.<i>}
 * @param callDepth how many nested calls the flow can make at most (0 when it calls nothing); the
 *        compiler uses it to reject chains deeper than the call depth limit
 */
public record CompiledFlow(String id, StepPath path, List<Step> steps, int callDepth) {

    public CompiledFlow {
        steps = List.copyOf(steps);
    }
}
