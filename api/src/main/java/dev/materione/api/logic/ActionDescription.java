package dev.materione.api.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.Objects;

/**
 * What the editor, the docs and the node palette know about an {@link ActionType}: its
 * {@link SelfDescription} (display name, summary, parameter schema, activation sentence), the
 * palette {@link LogicCategory}, and the scalar shorthand. One description per type, ever (D5): the
 * form the studio draws and the parameters {@link ActionType#parse} reads are the same schema.
 *
 * @param self display metadata and the parameter schema
 * @param category the palette group
 * @param shorthand the id of the parameter a bare scalar stands for ({@code message: "hi"} is
 *        {@code text}), or {@code null} when the action only takes a mapping; it must name a
 *        parameter declared in {@code self}
 */
public record ActionDescription(SelfDescription self, LogicCategory category, String shorthand) {

    public ActionDescription {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(category, "category");
        shorthand = shorthand == null || shorthand.isBlank() ? null : shorthand;
        if (shorthand != null) {
            String declared = shorthand;
            if (self.parameters().stream().map(ParameterSchema::id).noneMatch(declared::equals)) {
                throw new IllegalArgumentException(
                        "shorthand '" + shorthand + "' is not a declared parameter of '" + self.displayName() + "'");
            }
        }
    }

    /** A description whose action takes a parameter mapping only. */
    public static ActionDescription of(SelfDescription self, LogicCategory category) {
        return new ActionDescription(self, category, null);
    }
}
