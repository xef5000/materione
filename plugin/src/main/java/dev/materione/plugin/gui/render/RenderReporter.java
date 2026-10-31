package dev.materione.plugin.gui.render;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCode;

/**
 * Where a render reports a problem it worked around: an item source that could not resolve, a condition or
 * template that failed. The render continues either way; the adapter decides how often the problem is
 * logged (the window renders every refresh, so the adapter must rate-limit).
 */
@FunctionalInterface
public interface RenderReporter {

    /**
     * Reports one problem.
     *
     * @param code the diagnostic code it is about
     * @param gui the GUI being rendered
     * @param layerId the layer the problem belongs to
     * @param message what went wrong, in the author's terms
     */
    void problem(DiagnosticCode code, NamespacedId gui, String layerId, String message);

    /** Reports nothing: for a test that does not care. */
    RenderReporter SILENT = (code, gui, layerId, message) -> { };
}
