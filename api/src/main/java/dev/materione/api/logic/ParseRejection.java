package dev.materione.api.logic;

import dev.materione.api.diagnostics.DiagnosticCode;
import java.util.Objects;

/**
 * A parameter the type rejects, with the diagnostic code it should be reported under. A type throws
 * it from {@link ActionType#parse} or {@link ConditionType#parse} when the problem has a code of its
 * own (for example {@code MTR-0063} for an undeclared variable); any other
 * {@link IllegalArgumentException} is reported as the generic rejected step ({@code MTR-0057}).
 */
public final class ParseRejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient DiagnosticCode code;

    public ParseRejection(DiagnosticCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** The diagnostic the rejected step is reported under. */
    public DiagnosticCode code() {
        return code;
    }
}
