package dev.materione.behavior.logic.authoring;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import java.util.List;

/**
 * An edit the logic authoring surface refuses: a structural problem with the request (a bad path, an
 * unknown step kind, a move into a subtree). Thrown while an edit applies to the working copy, so the
 * whole request is refused and nothing is written. Carries the path it is about as the diagnostic's
 * target.
 */
final class LogicRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DiagnosticCode code;
    private final String path;

    LogicRefusal(DiagnosticCode code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    /** A refused step edit (MTR-0057) at {@code path}. */
    static LogicRefusal step(String path, String message) {
        return new LogicRefusal(DiagnosticCodes.LOGIC_STEP_REJECTED, path, message);
    }

    /** A refused rule edit (MTR-0021) at {@code path}. */
    static LogicRefusal rule(String path, String message) {
        return new LogicRefusal(DiagnosticCodes.BEHAVIOR_RULE_INVALID, path, message);
    }

    /** The refusal as the diagnostic the API returns, naming the resource and the step path. */
    Diagnostic diagnostic(NamespacedId resource) {
        DiagnosticTarget target = path == null || path.isEmpty()
                ? null : new DiagnosticTarget(List.of(path.split("\\.", -1)));
        return new Diagnostic(code, Severity.ERROR, getMessage(), resource, null,
                "the edit was refused and nothing was written", target);
    }
}
