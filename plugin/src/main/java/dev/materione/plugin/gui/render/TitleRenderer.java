package dev.materione.plugin.gui.render;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.LogicContext;
import dev.materione.content.gui.GuiTextRules;
import dev.materione.content.gui.GuiTitle;
import dev.materione.content.gui.GuiVisualPlan;
import dev.materione.plugin.gui.dynamic.TitleProgram;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Renders ordered title pieces and the corresponding visible click footprints. */
public final class TitleRenderer {
    private final RenderReporter reporter;

    public TitleRenderer(RenderReporter reporter) {
        this.reporter = reporter;
    }

    public record Result(String title, Set<String> hidden, Map<String, Set<Integer>> coverage) {}

    public Result render(NamespacedId gui, int rows, TitleProgram program, LogicContext context, long ticks) {
        GuiVisualPlan plan = program.plan();
        var title = new GuiTitle(plan.namespace(), plan.background(), plan.backgroundAdvance());
        var hidden = new HashSet<String>();
        var coverage = new HashMap<String, Set<Integer>>();
        var visible = new HashMap<String, Boolean>();
        for (TitleProgram.Piece compiled : program.pieces()) {
            var piece = compiled.source();
            boolean show = visible.computeIfAbsent(piece.layer(), ignored -> visible(gui, compiled, context));
            if (!show) {
                hidden.add(piece.layer());
                continue;
            }
            if (piece instanceof GuiVisualPlan.Raster raster) {
                var frame = raster.frame(ticks);
                title.glyph(frame.codepoint(), frame.advance());
                coverage.put(piece.layer(), frame.slots());
            } else if (piece instanceof GuiVisualPlan.Text text) {
                String value;
                try {
                    value = compiled.template() == null ? text.text() : compiled.template().render(context);
                    String problem = value == null ? "template returned null"
                            : GuiTextRules.problem(value, text.font(), text.y(), false);
                    if (problem != null) {
                        throw new IllegalArgumentException(problem);
                    }
                    title.place(text.x(), text.y(), value, text.font(), text.argb());
                    coverage.computeIfAbsent(piece.layer(), ignored -> new HashSet<>())
                            .addAll(GuiTextRules.slots(text.x(), text.y(), value, text.font(), rows));
                } catch (RuntimeException failure) {
                    reporter.problem(DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, gui, piece.layer(),
                            "text could not be rendered: " + failure.getMessage());
                    coverage.putIfAbsent(piece.layer(), Set.of());
                }
            }
        }
        return new Result(title.build(), Set.copyOf(hidden), Map.copyOf(coverage));
    }

    private boolean visible(NamespacedId gui, TitleProgram.Piece piece, LogicContext context) {
        if (piece.invalid()) {
            return false;
        }
        try {
            return piece.condition() == null || piece.condition().test(context);
        } catch (RuntimeException failure) {
            reporter.problem(DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, gui, piece.source().layer(),
                    "visible_if could not be evaluated: " + failure.getMessage());
            return false;
        }
    }
}
