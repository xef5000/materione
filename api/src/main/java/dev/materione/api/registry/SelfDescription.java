package dev.materione.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Human-facing metadata every registry entry carries. Self-description is
 * what makes the "same registration path for built-ins and addons" rule (D5)
 * pay off: the editor, the docs generator, and tab completion all render from
 * this — one schema source per entry, ever.
 *
 * @param displayName short human name (editor labels)
 * @param description one-paragraph explanation shown as editor help text
 * @param parameters declared parameters; may be empty
 * @param sinceVersion plugin version this entry first appeared in
 */
public record SelfDescription(
        String displayName,
        String description,
        List<ParameterSchema> parameters,
        String sinceVersion) {

    public SelfDescription {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** Minimal description with no parameters. */
    public static SelfDescription of(String displayName, String description) {
        return new SelfDescription(displayName, description, List.of(), "0.1.0");
    }
}
