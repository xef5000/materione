package dev.materione.behavior.flow;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.List;

/**
 * State shared by everything one {@link FlowCompiler} call compiles: the owning resource, the
 * registries, and the problems found so far. Compilation is pure, so the problems come back to the
 * caller as data and nothing here touches the bus.
 */
final class CompileContext {

    final NamespacedId owner;
    final LogicLookup lookup;
    /** The owner's pack and declared variables, what a step's reader needs to check a {@code set_var}. */
    final CompileEnvironment environment;
    /** Whether diagnostics point at a field of the resource; false for rules generated from mechanics. */
    final boolean targets;
    final List<Diagnostic> problems = new ArrayList<>();
    /** Steps skipped so far; a {@code require} compares it before and after its body to fail closed. */
    int skippedSteps;

    CompileContext(NamespacedId owner, LogicLookup lookup, boolean targets) {
        this.owner = owner;
        this.lookup = lookup;
        this.targets = targets;
        this.environment = CompileEnvironment.of(owner.namespace(), lookup::variable);
    }

    void report(DiagnosticCode code, Severity severity, String subject, StepPath path, String problem, String hint) {
        String where = path.isEmpty() ? "" : " at " + path;
        problems.add(new Diagnostic(
                code, severity, owner + " " + subject + where + ": " + problem,
                owner, null, hint, targets ? path.target() : null));
    }
}
