package dev.materione.plugin.runtime.blocks;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.DiagnosticCodes;
import java.lang.reflect.Field;

/**
 * Probes Paper's {@code blocks-updates.disable-noteblock-updates} /
 * {@code disable-tripwire-updates} config flags — the primary physics
 * suppression for state-as-identity hosts (both incumbents converge here:
 * event-level suppression of every neighbor update is "very taxing" and
 * buggy by comparison). The flags live in server-internal classes, so the
 * probe is reflective and honest about failure: an unreadable config counts
 * as inactive and the fallback listeners stay hot.
 */
public final class PhysicsProbe {

    /** Probe outcome per flag; {@code error} set means reflection could not read them. */
    public record Result(
            boolean noteBlockSuppressed,
            boolean tripwireSuppressed,
            boolean chorusPlantSuppressed,
            String error) {
        public boolean fullySuppressed() {
            return error == null && noteBlockSuppressed && tripwireSuppressed && chorusPlantSuppressed;
        }

        /** The flags that are off, for the operator-facing diagnostic. */
        public String missing() {
            var missing = new java.util.ArrayList<String>();
            if (!noteBlockSuppressed) {
                missing.add("disable-noteblock-updates");
            }
            if (!tripwireSuppressed) {
                missing.add("disable-tripwire-updates");
            }
            if (!chorusPlantSuppressed) {
                missing.add("disable-chorus-plant-updates");
            }
            return String.join(", ", missing);
        }
    }

    private PhysicsProbe() {}

    /**
     * Reads the live global configuration reflectively. Never throws.
     * Field names verified against Paper 1.21.4
     * ({@code GlobalConfiguration.blockUpdates.disable*Updates}); a future
     * rename degrades to the error result, never to a crash.
     */
    public static Result probe() {
        try {
            Class<?> global = Class.forName(
                    "io.papermc.paper.configuration.GlobalConfiguration");
            Object config = global.getMethod("get").invoke(null);
            Object blockUpdates = field(config, "blockUpdates");
            return new Result(
                    booleanFlag(blockUpdates, "disableNoteblockUpdates"),
                    booleanFlag(blockUpdates, "disableTripwireUpdates"),
                    booleanFlag(blockUpdates, "disableChorusPlantUpdates"),
                    null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new Result(false, false, false,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Publishes MTR-0025 when suppression is not fully active (once, at boot). */
    public static Result enforceOrReport(DiagnosticsBus diagnostics) {
        Result result = probe();
        if (!result.fullySuppressed()) {
            String detail = result.error() != null
                    ? "config unreadable (" + result.error() + ")"
                    : "off: " + result.missing();
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.PHYSICS_SUPPRESSION_INACTIVE, Severity.WARN,
                    "Paper block-update suppression inactive: " + detail,
                    null, null,
                    "set in config/paper-global.yml under blocks-updates: "
                            + "disable-noteblock-updates, disable-tripwire-updates, "
                            + "disable-chorus-plant-updates to true "
                            + "(fallback listeners cover the gap at a performance cost)"));
        }
        return result;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getField(name);
        return field.get(owner);
    }

    private static boolean booleanFlag(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getField(name);
        return field.getBoolean(owner);
    }
}
