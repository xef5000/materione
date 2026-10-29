package dev.materione.api.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.Objects;

/**
 * What the editor knows about a {@link ConditionType}: its {@link SelfDescription}, palette
 * {@link LogicCategory} and scalar shorthand ({@code has_money: 250} is {@code amount}). The same
 * shape as {@link ActionDescription}, for the same reason.
 *
 * @param self display metadata and the parameter schema
 * @param category the palette group
 * @param shorthand the parameter a bare scalar stands for, or {@code null}; it must name a
 *        parameter declared in {@code self}
 */
public record ConditionDescription(SelfDescription self, LogicCategory category, String shorthand) {

    public ConditionDescription {
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

    /** A description whose condition takes a parameter mapping only. */
    public static ConditionDescription of(SelfDescription self, LogicCategory category) {
        return new ConditionDescription(self, category, null);
    }
}
