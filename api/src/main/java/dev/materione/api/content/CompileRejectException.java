package dev.materione.api.content;

import dev.materione.api.diagnostics.DiagnosticCode;

/**
 * A compile hook rejecting one definition with a specific, documented
 * diagnostic code — the typed alternative to a generic crash (which would
 * surface as MTR-0011). The compiler publishes the carried code, quarantines
 * the resource and continues with the rest of the pack (fail-soft, D6).
 * Used where a rejection is expected and explainable: exhausted allocation
 * spaces, platform-gated features.
 */
public final class CompileRejectException extends RuntimeException {

    private final DiagnosticCode code;
    private final String hint;

    public CompileRejectException(DiagnosticCode code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public DiagnosticCode code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
