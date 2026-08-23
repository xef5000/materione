package dev.materione.core.diagnostics;

import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.Severity;
import java.util.List;

/**
 * <p>The diagnostic-code registry. Codes are API: users search them, docs
 * document them, addons emit them — never renumber, never reuse. Ranges:
 * M0 bootstrap MTR-0001..0006, M1 pipeline MTR-0007..0016, pack output
 * MTR-0017..0018, M2 world systems MTR-0019...
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

    // M2 — world systems (runtime-systems §4/§5)

    /** A block definition needs a state slot but its technique's space is exhausted; the resource is skipped. */
    public static final DiagnosticCode BLOCK_STATE_EXHAUSTED =
            new DiagnosticCode("MTR-0019", Severity.ERROR, "Block-state allocation space exhausted");

    /** Two sources claim the same key of a vanilla-namespace merged file; the first wins deterministically. */
    public static final DiagnosticCode VANILLA_OVERRIDE_CONFLICT =
            new DiagnosticCode("MTR-0020", Severity.WARN, "Conflicting vanilla-file override key");

    /** A behavior rule is structurally invalid (missing event, no actions, unknown fields); the rule is skipped. */
    public static final DiagnosticCode BEHAVIOR_RULE_INVALID =
            new DiagnosticCode("MTR-0021", Severity.WARN, "Behavior rule invalid; skipped");

    /** A rule condition or interpolation could not be parsed at load; the rule is skipped (MTR-0021's expression case). */
    public static final DiagnosticCode EXPRESSION_INVALID =
            new DiagnosticCode("MTR-0022", Severity.WARN, "Expression failed to parse; rule skipped");

    /** A runtime action or evaluation failed; the chain continues, logging is rate-limited (plugin.md §5). */
    public static final DiagnosticCode ACTION_FAILED =
            new DiagnosticCode("MTR-0023", Severity.WARN, "Behavior action failed; chain continues");

    /** A placed block's state decodes to one custom block but its chunk PDC records another (or nothing). */
    public static final DiagnosticCode BLOCK_DATA_MISMATCH =
            new DiagnosticCode("MTR-0024", Severity.WARN, "Placement data does not match its recorded block");

    /** Paper's block-update suppression flags are off (or unreadable); fallback listeners cover the gaps. */
    public static final DiagnosticCode PHYSICS_SUPPRESSION_INACTIVE =
            new DiagnosticCode("MTR-0025", Severity.WARN, "Paper block-update suppression inactive");

    /** A recorded placement references a definition that no longer exists (or a stale index entry). */
    public static final DiagnosticCode ORPHANED_PLACEMENT =
            new DiagnosticCode("MTR-0026", Severity.INFO, "Placement references an unknown definition");

    /** An item declares a mechanic that is unknown, or parameters the mechanic rejects; it is skipped. */
    public static final DiagnosticCode UNKNOWN_MECHANIC =
            new DiagnosticCode("MTR-0027", Severity.WARN, "Item mechanic skipped");

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
            PACK_FORMAT_UNKNOWN_VERSION,
            BLOCK_STATE_EXHAUSTED,
            VANILLA_OVERRIDE_CONFLICT,
            BEHAVIOR_RULE_INVALID,
            EXPRESSION_INVALID,
            ACTION_FAILED,
            BLOCK_DATA_MISMATCH,
            PHYSICS_SUPPRESSION_INACTIVE,
            ORPHANED_PLACEMENT,
            UNKNOWN_MECHANIC);

    private DiagnosticCodes() {}

    public static List<DiagnosticCode> all() {
        return ALL;
    }
}
