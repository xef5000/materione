package dev.materione.content.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;

/**
 * The schema entries of the logic keys that resources share (logic.md §2.4, §6.1, api.md §3.3). They
 * are declared once here so every content type that carries {@code flows:} lists it the same way, and
 * the editor learns the keys from the self-description (#221) rather than by a hard-coded list.
 */
public final class LogicSchemas {

    private LogicSchemas() {}

    /** The {@code behavior:} rules, as the GUI declares them (items, blocks and furniture declare their own). */
    public static ParameterSchema behavior() {
        return new ParameterSchema("behavior", ParameterType.LIST, false, null,
                "ECA rules fired by this GUI's clicks and opens", null, null)
                .withEditor(new ParameterSchema.EditorMetadata(
                        "When… do…", "behavior", 10, false, "behavior-rules", "", "", true, "behaviour", "activity",
                        "React to clicks and opens with conditions and actions"));
    }

    /** The named flows {@code call:} runs from this resource's rules (logic.md §2.4). */
    public static ParameterSchema flows() {
        return new ParameterSchema("flows", ParameterType.RAW, false, null,
                "Named flows (id to steps), run from this resource's rules with call: <id>", null, null)
                .withEditor(new ParameterSchema.EditorMetadata("Flows", "behavior", 30, true, "advanced", "", ""));
    }

    /** The {@code open:} block of a GUI (logic.md §6.1): its commands, arguments, permission, requirement and deny. */
    public static ParameterSchema open() {
        return new ParameterSchema("open", ParameterType.RAW, false, null,
                "How the GUI opens: commands, typed arguments, permission, requirement and deny steps", null, null)
                .withEditor(new ParameterSchema.EditorMetadata("Open", "behavior", 40, true, "advanced", "", ""));
    }
}
