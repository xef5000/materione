package dev.materione.api.diagnostics;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * One structured failure or warning, anywhere in the system. Diagnostics are
 * data, not log lines (plugin architecture §5): the same record surfaces
 * in-game (clickable, with hint), in the editor (inline), and in
 * {@code /materione doctor}.
 *
 * @param resource the content resource involved, if any
 * @param location source coordinates, if the failure came from a file
 * @param hint a suggested fix, if one is known
 */
public record Diagnostic(
        DiagnosticCode code,
        Severity severity,
        String message,
        NamespacedId resource,
        SourceLocation location,
        String hint) {

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public static Diagnostic error(DiagnosticCode code, String message) {
        return new Diagnostic(code, Severity.ERROR, message, null, null, null);
    }

    public static Diagnostic warn(DiagnosticCode code, String message) {
        return new Diagnostic(code, Severity.WARN, message, null, null, null);
    }

    public static Diagnostic info(DiagnosticCode code, String message) {
        return new Diagnostic(code, Severity.INFO, message, null, null, null);
    }
}
