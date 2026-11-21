package dev.materione.api.logic;

import dev.materione.api.diagnostics.DiagnosticCode;
import java.util.Objects;

/**
 * A condition refused a value its content gave it (logic.md section 42, {@code MTR-0120}). A condition returns a boolean
 * and has no outcome to carry a code, so it throws this instead: its test counts as false, which is the contract of
 * {@link ConditionType#test}, and the engine reports the code (rate-limited) and traces the refusal. An action does not
 * throw it; it returns {@link ActionOutcome#refused}.
 */
public final class CodedRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient DiagnosticCode code;

    /**
     * @param code the diagnostic the refusal reports
     * @param message what was refused and why, for the trace and the diagnostic
     */
    public CodedRefusal(DiagnosticCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** The diagnostic code the engine reports for this refusal. */
    public DiagnosticCode code() {
        return code;
    }
}
