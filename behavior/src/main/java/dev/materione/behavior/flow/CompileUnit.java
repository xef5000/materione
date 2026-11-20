package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.expr.ExprRoots;
import dev.materione.behavior.expr.Expression;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.interp.Template;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * One rule or one named flow being compiled: its name for messages, and the variable roots its
 * {@code ${...}} paths are checked against (logic.md §2.6). A fresh unit per rule and per flow, so
 * compiling a flow in the middle of a rule (a {@code call} links it on first use) never disturbs
 * the rule's own state.
 */
final class CompileUnit {

    /** Roots every run has, whatever the trigger (logic.md §2.6). */
    static final Set<String> UNIVERSAL_ROOTS = Set.of("player", "server", "vars");

    /** The root of the stack a run is about; a trigger on an item host provides it (logic.md section 38). */
    static final String ITEM_ROOT = "item";

    final CompileContext context;
    private final String subject;
    /** The roots a path may start with, or {@code null} when the trigger's schema is unknown. */
    private final Set<String> roots;
    private final String rootsOrigin;
    private final Set<String> warned = new HashSet<>();

    CompileUnit(CompileContext context, String subject, Set<String> roots, String rootsOrigin) {
        this.context = context;
        this.subject = subject;
        this.roots = roots;
        this.rootsOrigin = rootsOrigin;
    }

    String subject() {
        return subject;
    }

    /** Reports a problem at {@code path}. */
    void problem(DiagnosticCode code, Severity severity, StepPath path, String message, String hint) {
        context.report(code, severity, subject, path, message, hint);
    }

    /** The structural case: the author's intent is unreadable, so the unit is skipped. */
    Skip structural(StepPath path, String message, String hint) {
        problem(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, path, message, hint);
        return Skip.unit();
    }

    // --- variable roots ---------------------------------------------------------

    /**
     * Whether a path may start with {@code root} here: true when the trigger's schema is unknown. {@code event.target}
     * asks it of {@code target}, the root the entity triggers add (logic.md §39).
     */
    boolean mayRead(String root) {
        return roots == null || roots.contains(root);
    }

    /** What the roots belong to, for a message ({@code the trigger entity.hit does not provide}). */
    String rootsOrigin() {
        return rootsOrigin;
    }

    /** Checks the roots of every {@code ${...}} in a template. */
    void checkTemplate(String source, StepPath path) {
        if (roots == null || source == null || !source.contains("${")) {
            return;
        }
        var found = new LinkedHashSet<String>();
        for (Expression expression : Template.compile(source, context.lookup.functions()).expressions()) {
            ExprRoots.collect(expression.tree(), found);
        }
        warnUnknown(found, path);
    }

    /** Checks the roots of a bare expression's source text. */
    void checkExpressionSource(String source, StepPath path) {
        if (roots == null) {
            return;
        }
        var found = new LinkedHashSet<String>();
        ExprRoots.collect(ExpressionParser.parse(source, context.lookup.functions()).tree(), found);
        warnUnknown(found, path);
    }

    /** Checks the roots of an already parsed expression. */
    void checkExpression(ExprNode tree, StepPath path) {
        if (roots == null) {
            return;
        }
        var found = new LinkedHashSet<String>();
        ExprRoots.collect(tree, found);
        warnUnknown(found, path);
    }

    /**
     * Checks the roots a step declared it needs ({@code ParamReader.requireRoot}): a step that names an item-scoped
     * variable needs a trigger that provides {@code item} (logic.md §38). The same warning as an unknown root in a
     * template, so a reference that is not written as {@code ${...}} gets the check a read does.
     */
    void checkRequiredRoots(java.util.Map<String, String> required, StepPath path) {
        if (roots == null) {
            return;
        }
        for (var need : required.entrySet()) {
            String root = need.getKey();
            if (!roots.contains(root) && warned.add(path + "|" + root)) {
                problem(DiagnosticCodes.FLOW_UNKNOWN_CONTEXT_ROOT, Severity.WARN, path,
                        "'" + root + "' is not a variable " + rootsOrigin + ": " + need.getValue(),
                        "known roots: " + String.join(", ", new TreeSet<>(roots))
                                + "; the step cannot name an item stack here and fails when it runs");
            }
        }
    }

    private void warnUnknown(Set<String> found, StepPath path) {
        for (String root : found) {
            if (!roots.contains(root) && warned.add(path + "|" + root)) {
                problem(DiagnosticCodes.FLOW_UNKNOWN_CONTEXT_ROOT, Severity.WARN, path,
                        "'" + root + "' is not a variable " + rootsOrigin,
                        "known roots: " + String.join(", ", new TreeSet<>(roots))
                                + (ITEM_ROOT.equals(root)
                                        ? "; item.* and an item-scoped variable (vars.item.*) need a trigger that is about"
                                                + " an item stack, and read as empty or as their default until fixed"
                                        : "; the value reads as empty until fixed"));
            }
        }
    }
}
