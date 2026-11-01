package dev.materione.plugin.gui.dynamic;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.TemplateText;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.expr.ExprRoots;
import dev.materione.behavior.interp.Template;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.content.gui.GuiVisualPlan;
import dev.materione.content.gui.item.GuiTemplates;
import java.util.ArrayList;
import java.util.List;

/** Per-viewer title pieces with their conditions and templates compiled at load. */
public record TitleProgram(GuiVisualPlan plan, List<Piece> pieces) {
    public TitleProgram {
        pieces = List.copyOf(pieces);
    }

    public record Piece(GuiVisualPlan.Piece source, Condition condition, TemplateText template, boolean invalid) {}
    public record Result(TitleProgram program, List<Diagnostic> problems) {
        public Result {
            problems = List.copyOf(problems);
        }
    }

    public static Result compile(NamespacedId id, GuiVisualPlan plan, LogicLookup lookup) {
        var pieces = new ArrayList<Piece>();
        var problems = new ArrayList<Diagnostic>();
        for (GuiVisualPlan.Piece piece : plan.pieces()) {
            Condition condition = null;
            boolean invalid = false;
            if (piece.visibleIf() != null) {
                if (lookup == null) {
                    invalid = true;
                } else {
                    var result = FlowCompiler.compileCondition(id, "layer " + piece.layer(), piece.visibleIf(),
                            ItemLayerCompiler.ROOTS, lookup);
                    condition = result.condition().orElse(null);
                    invalid = condition == null;
                    result.problems().forEach(problem -> problems.add(new Diagnostic(problem.code(),
                            problem.severity(), problem.message(), id, null, problem.hint(),
                            DiagnosticTarget.field("layers", String.valueOf(piece.order()), "visible_if"))));
                }
            }
            TemplateText template = null;
            if (piece instanceof GuiVisualPlan.Text text && GuiTemplates.isTemplated(text.text())) {
                try {
                    if (lookup == null || unterminated(text.text())) {
                        throw new IllegalArgumentException("template expression is unavailable or not closed");
                    }
                    template = EngineExpressions.with(lookup.functions()).template(text.text());
                    var roots = new java.util.LinkedHashSet<String>();
                    Template.compile(text.text(), lookup.functions()).expressions()
                            .forEach(expression -> ExprRoots.collect(expression.tree(), roots));
                    for (String root : roots) {
                        if (!ItemLayerCompiler.ROOTS.contains(root)) {
                            problems.add(new Diagnostic(DiagnosticCodes.FLOW_UNKNOWN_CONTEXT_ROOT, Severity.WARN,
                                    "GUI " + id + " layer " + piece.layer() + " text reads unknown root '" + root + "'",
                                    id, null, "known roots: " + String.join(", ",
                                            new java.util.TreeSet<>(ItemLayerCompiler.ROOTS)),
                                    DiagnosticTarget.field("layers", String.valueOf(piece.order()), "text")));
                        }
                    }
                } catch (IllegalArgumentException failure) {
                    invalid = true;
                    problems.add(new Diagnostic(DiagnosticCodes.EXPRESSION_INVALID, Severity.WARN,
                            "GUI " + id + " layer " + piece.layer() + ": " + failure.getMessage(), id, null,
                            "fix the text template", DiagnosticTarget.field("layers",
                                    String.valueOf(piece.order()), "text")));
                }
            }
            pieces.add(new Piece(piece, condition, template, invalid));
        }
        return new Result(new TitleProgram(plan, pieces), problems);
    }

    private static boolean unterminated(String text) {
        int at = 0;
        while ((at = text.indexOf("${", at)) >= 0) {
            int end = text.indexOf('}', at + 2);
            if (end < 0) {
                return true;
            }
            at = end + 1;
        }
        return false;
    }
}
