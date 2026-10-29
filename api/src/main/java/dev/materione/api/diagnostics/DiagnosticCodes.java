package dev.materione.api.diagnostics;

import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.Severity;
import java.util.List;

/**
 * <p>The diagnostic-code registry. Codes are API: users search them, docs
 * document them, addons emit them — never renumber, never reuse. Ranges:
 * M0 bootstrap MTR-0001..0006, M1 pipeline MTR-0007..0016, pack output
 * MTR-0017..0018, M2 world systems MTR-0019..0029, M3 web editor
 * MTR-0030...
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

    /** A user-shipped atlas json could not be parsed, so generated atlas sources were dropped with it — affected textures render as missing in game. */
    public static final DiagnosticCode TEXTURE_NOT_IN_ATLAS =
            new DiagnosticCode("MTR-0017", Severity.WARN, "Atlas source could not be generated");

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

    /** A change cannot be applied hot; it is staged and applied at the next boot (§4.5). */
    public static final DiagnosticCode RESTART_STAGED =
            new DiagnosticCode("MTR-0028", Severity.WARN, "Change staged for restart-required application");

    /** Staged restart-required changes were applied during this boot, before content load. */
    public static final DiagnosticCode RESTART_APPLIED =
            new DiagnosticCode("MTR-0029", Severity.INFO, "Staged restart changes applied at boot");

    // M3 — web editor transport (web.md §4/§5)

    /** The embedded editor listener failed to start or bind its port. */
    public static final DiagnosticCode EDITOR_LISTENER_FAILED =
            new DiagnosticCode("MTR-0030", Severity.ERROR, "Editor listener failed to start");

    /** `/materione editor` created a session and printed its capability URL. */
    public static final DiagnosticCode EDITOR_SESSION_CREATED =
            new DiagnosticCode("MTR-0031", Severity.INFO, "Editor session created; awaiting browser");

    /** A connecting editor completed the handshake and was approved in game. */
    public static final DiagnosticCode EDITOR_PEER_TRUSTED =
            new DiagnosticCode("MTR-0032", Severity.INFO, "Editor peer approved in game; session active");

    /** A frame/envelope failed signature, sequence, or decryption checks; that session is dropped. */
    public static final DiagnosticCode EDITOR_PROTOCOL_VIOLATION =
            new DiagnosticCode("MTR-0033", Severity.WARN, "Editor protocol violation; session dropped");

    /** An editor save carried a stale revision; the document changed elsewhere meanwhile. */
    public static final DiagnosticCode EDITOR_DOCUMENT_CONFLICT =
            new DiagnosticCode("MTR-0034", Severity.WARN, "Editor save rejected: document changed elsewhere");

    /** An asset upload failed verification (bad manifest, chunk hash, or envelope). */
    public static final DiagnosticCode EDITOR_ASSET_REJECTED =
            new DiagnosticCode("MTR-0035", Severity.WARN, "Editor asset transfer rejected");

    /** The editor listener is bound beyond loopback without explicit configuration. */
    public static final DiagnosticCode EDITOR_BIND_EXPOSED =
            new DiagnosticCode("MTR-0036", Severity.WARN, "Editor listener bound beyond loopback");

    /** The persisted server identity keypair was unreadable; a new one replaced it. */
    public static final DiagnosticCode EDITOR_IDENTITY_REBUILT =
            new DiagnosticCode("MTR-0037", Severity.WARN, "Server editor identity key rebuilt");

    /** A block and a furniture share one id: both claim the same auto placement item; the furniture wins on click. */
    public static final DiagnosticCode AMBIGUOUS_PLACEMENT_ID =
            new DiagnosticCode("MTR-0038", Severity.WARN, "Block and furniture share an id");

    // M4 — glyphs, fonts, sounds, paintings (runtime-systems §6/§10)

    /** A glyph references a texture the pack does not ship. */
    public static final DiagnosticCode GLYPH_TEXTURE_MISSING =
            new DiagnosticCode("MTR-0039", Severity.ERROR, "Glyph texture asset missing");

    /** A sound definition references an .ogg file the pack does not ship. */
    public static final DiagnosticCode SOUND_SOURCE_MISSING =
            new DiagnosticCode("MTR-0040", Severity.ERROR, "Custom sound source missing");

    /** A painting texture's pixel size does not match its declared block dimensions. */
    public static final DiagnosticCode PAINTING_SIZE_MISMATCH =
            new DiagnosticCode("MTR-0041", Severity.ERROR, "Painting texture size mismatch");

    /** A jukebox song section is invalid (bounds, missing fields, or unplayable reference). */
    public static final DiagnosticCode JUKEBOX_SONG_INVALID =
            new DiagnosticCode("MTR-0042", Severity.ERROR, "Jukebox song definition invalid");

    /** The generated world datapack (paintings, jukebox songs) could not be written. */
    public static final DiagnosticCode DATAPACK_WRITE_FAILED =
            new DiagnosticCode("MTR-0043", Severity.ERROR, "World datapack artifact write failed");

    /** A text effect cannot be produced for the running client's shader format. */
    public static final DiagnosticCode TEXT_EFFECT_UNSUPPORTED =
            new DiagnosticCode("MTR-0044", Severity.ERROR, "Text effect unsupported on this version");

    /** The glyph codepoint space is used up (shift font's block reached). */
    public static final DiagnosticCode GLYPH_CODEPOINT_EXHAUSTED =
            new DiagnosticCode("MTR-0045", Severity.ERROR, "Glyph codepoint space exhausted");

    /** The deployed datapack never entered the server's pack repository. */
    public static final DiagnosticCode DATAPACK_NOT_ACCEPTED =
            new DiagnosticCode("MTR-0046", Severity.WARN, "World datapack not accepted by the server");

    /** The text-effect signal color band (#FF00FF downward) is used up. */
    public static final DiagnosticCode EFFECT_SIGNAL_EXHAUSTED =
            new DiagnosticCode("MTR-0047", Severity.ERROR, "Text effect signal space exhausted");

    /** An item authors a vanilla `components:` block, which nothing applies yet (#103). */
    public static final DiagnosticCode ITEM_COMPONENTS_NOT_APPLIED =
            new DiagnosticCode("MTR-0048", Severity.WARN, "Item components block not applied");

    /** A relay-mode editor session ended (channel closed/expired or reader fell behind). */
    public static final DiagnosticCode EDITOR_RELAY_SESSION_ENDED =
            new DiagnosticCode("MTR-0049", Severity.INFO, "Relay editor session ended");

    /** The editor relay was unreachable or quota-limited; local mode used as fallback. */
    public static final DiagnosticCode EDITOR_RELAY_UNREACHABLE =
            new DiagnosticCode("MTR-0050", Severity.WARN, "Editor relay unreachable; no cloud editor session opened");

    /** The read-only editor resource-summary projection exceeded its bounded in-memory budget. */
    public static final DiagnosticCode AUTHORING_SUMMARY_BUDGET_EXCEEDED =
            new DiagnosticCode("MTR-0051", Severity.WARN, "Resource summary exceeds its read budget");

    /**
     * A GUI layer could not be compiled (unknown component, invalid fields, a
     * duplicate or malformed id). Only that layer is skipped; the rest of the
     * GUI still compiles. The diagnostic targets {@code layers.<index>}.
     */
    public static final DiagnosticCode GUI_LAYER_SKIPPED =
            new DiagnosticCode("MTR-0052", Severity.WARN, "GUI layer skipped");

    /**
     * The GUI background codepoint block (U+E200..U+F8FF) is used up. The GUI is not
     * compiled; the rest of the pack is unaffected.
     */
    public static final DiagnosticCode GUI_BACKGROUND_EXHAUSTED =
            new DiagnosticCode("MTR-0053", Severity.ERROR, "GUI background codepoint space exhausted");

    /**
     * A GUI slot names an item that is not live at apply time (unknown id, or a vanilla
     * material that does not exist). The slot stays empty and the rest of the GUI is shown
     * (gui.md §5); the GUI itself is not quarantined.
     */
    public static final DiagnosticCode GUI_SLOT_ITEM_UNRESOLVED =
            new DiagnosticCode("MTR-0054", Severity.WARN, "GUI slot item is not live; the slot stays empty");

    /**
     * An actor asked for a runtime operation without the permission node it needs (for example
     * {@code materione.gui.open}). Nothing happens.
     */
    public static final DiagnosticCode PERMISSION_DENIED =
            new DiagnosticCode("MTR-0055", Severity.ERROR, "Permission denied for this operation");

    /**
     * Two documents in one pack declare the same resource id for the same content type (plugin.md
     * §5). The first document in path order keeps the id and the other declaration is ignored;
     * authoring refuses to create a second declaration with this code.
     */
    public static final DiagnosticCode DUPLICATE_RESOURCE_ID =
            new DiagnosticCode("MTR-0056", Severity.WARN, "Resource id declared in more than one file");

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
            UNKNOWN_MECHANIC,
            RESTART_STAGED,
            RESTART_APPLIED,
            EDITOR_LISTENER_FAILED,
            EDITOR_SESSION_CREATED,
            EDITOR_PEER_TRUSTED,
            EDITOR_PROTOCOL_VIOLATION,
            EDITOR_DOCUMENT_CONFLICT,
            EDITOR_ASSET_REJECTED,
            EDITOR_BIND_EXPOSED,
            EDITOR_IDENTITY_REBUILT,
            AMBIGUOUS_PLACEMENT_ID,
            GLYPH_TEXTURE_MISSING,
            SOUND_SOURCE_MISSING,
            PAINTING_SIZE_MISMATCH,
            JUKEBOX_SONG_INVALID,
            DATAPACK_WRITE_FAILED,
            TEXT_EFFECT_UNSUPPORTED,
            GLYPH_CODEPOINT_EXHAUSTED,
            DATAPACK_NOT_ACCEPTED,
            EFFECT_SIGNAL_EXHAUSTED,
            ITEM_COMPONENTS_NOT_APPLIED,
            EDITOR_RELAY_SESSION_ENDED,
            EDITOR_RELAY_UNREACHABLE,
            AUTHORING_SUMMARY_BUDGET_EXCEEDED,
            GUI_LAYER_SKIPPED,
            GUI_BACKGROUND_EXHAUSTED,
            GUI_SLOT_ITEM_UNRESOLVED,
            PERMISSION_DENIED,
            DUPLICATE_RESOURCE_ID);

    private DiagnosticCodes() {}

    public static List<DiagnosticCode> all() {
        return ALL;
    }
}
