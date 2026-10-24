package dev.materione.core.settings;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.DiagnosticCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads and writes the versioned settings file with fail-soft semantics:
 *
 * <ul>
 *   <li>missing file → defaults written, clean state;</li>
 *   <li>unparseable file → preserved as {@code settings.yml.broken-N},
 *       defaults used, MTR-0004 diagnostic — the server keeps booting;</li>
 *   <li>version missing/unreadable → MTR-0003 warning, treated as current;</li>
 *   <li>version from the future (downgrade) → MTR-0003 warning, values kept
 *       as-is (a downgrade must not destroy newer settings);</li>
 *   <li>older version → forward migration chain (v1→v1 is the M0 no-op).</li>
 * </ul>
 *
 * <p>Unknown keys are always preserved on write (lossless round-trip,
 * master §5.6).
 */
public final class SettingsService {

    /** Current settings schema version. */
    public static final int CURRENT_VERSION = 1;

    /** Loaded settings: the full ordered value map plus load-time diagnostics. */
    public record SettingsState(int version, Map<String, Object> values, List<Diagnostic> diagnostics) {
        public SettingsState {
            values = new LinkedHashMap<>(values);
            diagnostics = List.copyOf(diagnostics);
        }

        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String s ? s : fallback;
        }

        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean b ? b : fallback;
        }

        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Number n ? n.intValue() : fallback;
        }
    }

    private static final String FILE_NAME = "settings.yml";

    /** Ordered defaults written on first boot (golden-tested byte-for-byte). */
    public static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("version", CURRENT_VERSION);
        defaults.put("debug", false);
        defaults.put("delivery_host", "0.0.0.0");
        defaults.put("delivery_port", 8090);
        defaults.put("delivery_public_host", "");
        defaults.put("editor_enabled", true);
        defaults.put("editor_file_watcher", true);
        defaults.put("editor_host", "127.0.0.1");
        defaults.put("editor_port", 8089);
        defaults.put("editor_mode", "cloud");
        defaults.put("relay_base_url", "https://editor.materione.com");
        return defaults;
    }

    /** Dumps values deterministically (stable key order, no line splitting). */
    public static String dump(Map<String, Object> values) {
        DumperOptions options = new DumperOptions();
        options.setSplitLines(false);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options).dump(new LinkedHashMap<>(values));
    }

    /**
     * Validates a settings draft WITHOUT touching disk — the editor's
     * settings write path runs this before saving so a bad edit can never
     * produce an unbootable server. Empty list means the draft parses as a
     * mapping; anything else carries the failure the way {@link #load}
     * would report it (same code, actionable hint).
     */
    public static java.util.List<Diagnostic> validateDraft(String content) {
        try {
            Object loaded = new Yaml().load(content == null ? "" : content);
            if (!(loaded instanceof Map<?, ?> map) || map.isEmpty()) {
                return List.of(new Diagnostic(
                        DiagnosticCodes.SETTINGS_PARSE_FAILED, dev.materione.api.diagnostics.Severity.ERROR,
                        "settings must be a non-empty YAML mapping", null, null,
                        "keep the existing keys and edit values only"));
            }
            toStringObjectMap(map);
            return List.of();
        } catch (YAMLException e) {
            return List.of(new Diagnostic(
                    DiagnosticCodes.SETTINGS_PARSE_FAILED, dev.materione.api.diagnostics.Severity.ERROR,
                    "settings draft failed to parse: " + e.getMessage(), null, null,
                    "fix the YAML syntax and retry"));
        }
    }

    /** Writes values to the settings file (parent dirs created). */
    public static void write(Path settingsFile, Map<String, Object> values) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, dump(values));
    }

    /** Loads (or initializes) the settings file. Never throws. */
    public SettingsState load(Path settingsDir) {
        Objects.requireNonNull(settingsDir, "settingsDir");
        Path file = settingsDir.resolve(FILE_NAME);
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (!Files.exists(file)) {
            try {
                write(file, defaults());
            } catch (IOException e) {
                diagnostics.add(new Diagnostic(
                        DiagnosticCodes.SETTINGS_PARSE_FAILED, Severity.ERROR,
                        "could not write default settings: " + e.getMessage(), null, null,
                        "check directory permissions for " + settingsDir));
            }
            return new SettingsState(CURRENT_VERSION, defaults(), diagnostics);
        }

        Map<String, Object> raw;
        try {
            Object loaded = new Yaml().load(Files.newBufferedReader(file));
            raw = loaded instanceof Map<?, ?> map ? toStringObjectMap(map) : null;
            if (raw == null) {
                throw new YAMLException("settings file is not a mapping");
            }
        } catch (IOException | YAMLException e) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.SETTINGS_PARSE_FAILED, Severity.ERROR,
                    "settings file failed to parse: " + e.getMessage(), null, null,
                    "the broken file was preserved as " + preserveBroken(file)));
            return new SettingsState(CURRENT_VERSION, defaults(), diagnostics);
        }

        int version = readVersion(raw, diagnostics);
        raw = migrateForward(version, raw, diagnostics);
        return new SettingsState(version, raw, diagnostics);
    }

    private static int readVersion(Map<String, Object> raw, List<Diagnostic> diagnostics) {
        Object version = raw.get("version");
        if (version instanceof Number number) {
            return number.intValue();
        }
        diagnostics.add(new Diagnostic(
                DiagnosticCodes.SETTINGS_VERSION_UNREADABLE, Severity.WARN,
                "settings 'version' missing or not a number; assuming " + CURRENT_VERSION,
                null, null, "add 'version: " + CURRENT_VERSION + "' to settings.yml"));
        return CURRENT_VERSION;
    }

    /**
     * Forward migration chain. M0: v1 is current, so the chain is empty; a
     * future file (downgrade) passes through untouched with the warning
     * already emitted by the caller-visible diagnostics below.
     */
    private static Map<String, Object> migrateForward(
            int foundVersion, Map<String, Object> values, List<Diagnostic> diagnostics) {
        if (foundVersion > CURRENT_VERSION) {
            diagnostics.add(new Diagnostic(
                    DiagnosticCodes.SETTINGS_VERSION_UNREADABLE, Severity.WARN,
                    "settings version " + foundVersion + " is newer than this plugin knows ("
                            + CURRENT_VERSION + "); values kept, some may be ignored",
                    null, null, "downgraded plugin? newer settings preserved on write"));
        }
        // v1 -> CURRENT chain: no steps yet.
        return values;
    }

    private static String preserveBroken(Path file) {
        String name = "settings.yml.broken";
        int n = 1;
        while (Files.exists(file.resolveSibling(name + "-" + n))) {
            n++;
        }
        Path target = file.resolveSibling(name + "-" + n);
        try {
            Files.move(file, target);
            Files.writeString(file, dump(defaults()));
        } catch (IOException e) {
            // Best effort; defaults still returned in-memory.
        }
        return target.getFileName().toString();
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }
}
