package dev.materione.plugin.protection;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.protection.ProtectionMode;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The protection settings (protection.md section 4): two flat optional keys of {@code settings.yml}, not written to a fresh file,
 * in the convention of the {@code world_verbs_*} keys (logic.md §41). A value that is not one of the key's values falls back to
 * its default and says so once ({@code MTR-0123}); nothing else about the server is affected.
 *
 * @param mode {@code protection_mode}: {@code event}, {@code port} or {@code both} (default {@code both})
 * @param askWithoutActor {@code protection_no_actor}: {@code ask} (default) puts an actor-less change to the port,
 *        {@code allow} lets it through unasked
 */
public record ProtectionSettings(ProtectionMode mode, boolean askWithoutActor) {

    /** What a server gets when it writes nothing. */
    public static final ProtectionSettings DEFAULTS = new ProtectionSettings(ProtectionMode.DEFAULT, true);

    /** The behavior before the port existed: the event path alone. What a test that wants no port uses. */
    public static final ProtectionSettings EVENT_ONLY = new ProtectionSettings(ProtectionMode.EVENT, true);

    /** The settings key of the mode. */
    public static final String MODE_KEY = "protection_mode";

    /** The settings key of the actor-less switch. */
    public static final String NO_ACTOR_KEY = "protection_no_actor";

    /**
     * The settings {@code values} write. An absent key is the default; a present one that is not one of its values is the
     * default too, with one warning naming the key and what it takes.
     */
    public static ProtectionSettings read(Map<String, Object> values, Consumer<Diagnostic> diagnostics) {
        ProtectionMode mode = ProtectionMode.DEFAULT;
        Object rawMode = values.get(MODE_KEY);
        if (rawMode != null) {
            var parsed = ProtectionMode.parse(rawMode);
            if (parsed.isPresent()) {
                mode = parsed.get();
            } else {
                invalid(diagnostics, MODE_KEY, rawMode, "event, port or both", "both");
            }
        }
        boolean ask = true;
        Object rawAsk = values.get(NO_ACTOR_KEY);
        if (rawAsk != null) {
            String word = rawAsk instanceof String text ? text.trim().toLowerCase(Locale.ROOT) : "";
            if (word.equals("ask")) {
                ask = true;
            } else if (word.equals("allow")) {
                ask = false;
            } else {
                invalid(diagnostics, NO_ACTOR_KEY, rawAsk, "ask or allow", "ask");
            }
        }
        return new ProtectionSettings(mode, ask);
    }

    private static void invalid(Consumer<Diagnostic> diagnostics, String key, Object raw, String values, String fallback) {
        if (diagnostics == null) {
            return;
        }
        diagnostics.accept(new Diagnostic(DiagnosticCodes.PROTECTION_SETTING_INVALID, Severity.WARN,
                "setting " + key + " is " + raw + ", which is not " + values + "; the default " + fallback + " is used",
                null, null, "Fix the value in settings.yml, or remove the key to use the default."));
    }
}
