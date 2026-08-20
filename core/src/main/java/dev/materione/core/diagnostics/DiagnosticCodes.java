package dev.materione.core.diagnostics;

import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.Severity;
import java.util.List;

/**
 * The M0 diagnostic-code registry. Codes are API: users search them, docs
 * document them, addons emit them — never renumber, never reuse.
 */
public final class DiagnosticCodes {

    /** The running server is below the Materione floor (1.21.4+). */
    public static final DiagnosticCode PLATFORM_UNSUPPORTED =
            new DiagnosticCode("MTR-0001", Severity.ERROR, "Server version below the Materione floor");

    /** A bootstrap phase threw and was degraded. */
    public static final DiagnosticCode PHASE_FAILED =
            new DiagnosticCode("MTR-0002", Severity.ERROR, "Bootstrap phase failed and was degraded");

    /** settings.yml version missing or unreadable — proceeding with assumptions. */
    public static final DiagnosticCode SETTINGS_VERSION_UNREADABLE =
            new DiagnosticCode("MTR-0003", Severity.WARN, "Settings version missing or unreadable");

    /** settings.yml failed to parse; the broken file was preserved and defaults used. */
    public static final DiagnosticCode SETTINGS_PARSE_FAILED =
            new DiagnosticCode("MTR-0004", Severity.ERROR, "Settings file failed to parse; defaults in use");

    /** Storage initialization problem (dirs/format marker). */
    public static final DiagnosticCode STORAGE_INIT_FAILED =
            new DiagnosticCode("MTR-0005", Severity.ERROR, "Storage initialization failed");

    /** Command registration problem. */
    public static final DiagnosticCode COMMAND_REGISTRATION_FAILED =
            new DiagnosticCode("MTR-0006", Severity.ERROR, "Command registration failed");

    private static final List<DiagnosticCode> ALL = List.of(
            PLATFORM_UNSUPPORTED,
            PHASE_FAILED,
            SETTINGS_VERSION_UNREADABLE,
            SETTINGS_PARSE_FAILED,
            STORAGE_INIT_FAILED,
            COMMAND_REGISTRATION_FAILED);

    private DiagnosticCodes() {}

    public static List<DiagnosticCode> all() {
        return ALL;
    }
}
