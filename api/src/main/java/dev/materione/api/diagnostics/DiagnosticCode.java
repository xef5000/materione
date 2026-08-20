package dev.materione.api.diagnostics;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable diagnostic code. Codes are API (plugin architecture §5): users
 * search them, addons emit them, docs document them — never renumber or
 * reuse. Format {@code MTR-####}; ranges are allocated in
 * {@code dev.materione.core.diagnostics.DiagnosticCodes}
 * (MTR-0xxx bootstrap/platform/settings at M0).
 */
public record DiagnosticCode(String code, Severity defaultSeverity, String summary) {

    private static final Pattern FORMAT = Pattern.compile("MTR-\\d{4}");

    public DiagnosticCode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        Objects.requireNonNull(summary, "summary");
        if (!FORMAT.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Invalid diagnostic code '" + code + "': must match MTR-\\d{4}");
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
