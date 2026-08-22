package dev.materione.core.diagnostics;

import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.Severity;
import java.util.List;

/**
 * <p>The diagnostic-code registry. Codes are API: users search them, docs
 * document them, addons emit them — never renumber, never reuse. Ranges:
 * M0 bootstrap MTR-0001..0006, M1 pipeline MTR-0007..0016, pack output
 * MTR-0017..0018.
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

    // M1 — content pipeline (master §6, plugin architecture §4)

    /** A content file failed YAML parsing; the file is quarantined (D6). */
    public static final DiagnosticCode CONTENT_PARSE_FAILED =
            new DiagnosticCode("MTR-0007", Severity.ERROR, "Content file failed to parse; quarantined");

    /** A resource does not match its content-type schema (validation level 2). */
    public static final DiagnosticCode CONTENT_SCHEMA_INVALID =
            new DiagnosticCode("MTR-0008", Severity.ERROR, "Resource does not match its schema");

    /** A reference points at something that does not exist (validation level 3). */
    public static final DiagnosticCode UNRESOLVED_REFERENCE =
            new DiagnosticCode("MTR-0009", Severity.ERROR, "Unresolved reference");

    /** A resource was quarantined: invisible in game, visible in doctor/editor. */
    public static final DiagnosticCode CONTENT_QUARANTINED =
            new DiagnosticCode("MTR-0010", Severity.WARN, "Resource quarantined (broken, here's why)");

    /** A pipeline stage failed unexpectedly; the run aborted, live state untouched. */
    public static final DiagnosticCode PIPELINE_STAGE_FAILED =
            new DiagnosticCode("MTR-0011", Severity.ERROR, "Pipeline stage failed; run aborted cleanly");

    /** Scope resolution started/expanded a run — informational (§4.1). */
    public static final DiagnosticCode SCOPE_EXPANDED =
            new DiagnosticCode("MTR-0012", Severity.INFO, "Pipeline scope resolved");

    /** A pack manifest (pack.yml) is missing or structurally invalid. */
    public static final DiagnosticCode MANIFEST_INVALID =
            new DiagnosticCode("MTR-0013", Severity.ERROR, "Pack manifest invalid");

    /** Pack delivery failed; apply is NOT rolled back (plugin.md §4.6). */
    public static final DiagnosticCode DELIVERY_FAILED =
            new DiagnosticCode("MTR-0014", Severity.ERROR, "Pack delivery failed (apply not rolled back)");

    /** A player declined or failed the resource pack download. */
    public static final DiagnosticCode PACK_DECLINED =
            new DiagnosticCode("MTR-0015", Severity.WARN, "Player declined or failed the resource pack");

    /** The pack was published to the delivery backend. */
    public static final DiagnosticCode DELIVERY_PUBLISHED =
            new DiagnosticCode("MTR-0016", Severity.INFO, "Pack published to the delivery backend");

    // Pack output hardening

    /** An emitted texture sits outside the vanilla atlas conventions and no atlas source covers it — it renders as missing in game. */
    public static final DiagnosticCode TEXTURE_NOT_IN_ATLAS =
            new DiagnosticCode("MTR-0017", Severity.WARN, "Emitted texture not covered by any atlas source");

    /** The running Minecraft version is not in the known pack-format table; floor assumptions are advertised. */
    public static final DiagnosticCode PACK_FORMAT_UNKNOWN_VERSION =
            new DiagnosticCode("MTR-0018", Severity.WARN, "Server version not in the pack-format table");

    private static final List<DiagnosticCode> ALL = List.of(
            PLATFORM_UNSUPPORTED,
            PHASE_FAILED,
            SETTINGS_VERSION_UNREADABLE,
            SETTINGS_PARSE_FAILED,
            STORAGE_INIT_FAILED,
            COMMAND_REGISTRATION_FAILED,
            CONTENT_PARSE_FAILED,
            CONTENT_SCHEMA_INVALID,
            UNRESOLVED_REFERENCE,
            CONTENT_QUARANTINED,
            PIPELINE_STAGE_FAILED,
            SCOPE_EXPANDED,
            MANIFEST_INVALID,
            DELIVERY_FAILED,
            PACK_DECLINED,
            DELIVERY_PUBLISHED,
            TEXTURE_NOT_IN_ATLAS,
            PACK_FORMAT_UNKNOWN_VERSION);

    private DiagnosticCodes() {}

    public static List<DiagnosticCode> all() {
        return ALL;
    }
}
