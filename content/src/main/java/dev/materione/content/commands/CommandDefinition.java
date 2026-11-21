package dev.materione.content.commands;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code command} document (logic.md §40): the names that run it, its typed arguments and gate (the
 * {@link OpenSpec} a GUI's {@code open:} block also reads), whether the console may run it, and the one flow it
 * runs. {@code steps} and {@code flows} are the authored values with their keys made strings, ready for the flow
 * compiler; {@code raw} is the whole merged mapping, so a key this version does not know is kept.
 *
 * @param id the document's resource id ({@code shop:pay})
 * @param description the author's note, or null
 * @param spec names, arguments, permission, requirement and deny, as {@code open:} spells them
 * @param console whether a sender with no player (the console, a command block, RCON) may run it
 * @param steps the {@code do:} list as written; empty when the document has none
 * @param flows the {@code flows:} mapping as written, or null
 * @param raw the merged mapping
 * @param problems what the shape of the names, arguments and gate got wrong, each already a diagnostic
 */
public record CommandDefinition(
        NamespacedId id,
        String description,
        OpenSpec spec,
        boolean console,
        List<Object> steps,
        Map<String, Object> flows,
        Map<String, Object> raw,
        List<Diagnostic> problems) {

    public CommandDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
        raw = Map.copyOf(raw);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
