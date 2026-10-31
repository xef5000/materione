package dev.materione.api.logic;

import dev.materione.api.registry.SelfDescription;
import java.util.Objects;

/**
 * An item source's self-description (logic.md §4.4).
 *
 * @param self the name, summary and parameter schema, the same shape every registry entry describes with
 * @param shorthand the key a scalar payload is read under ({@code slot} for {@code player_slot: main_hand}),
 *     or {@code null} when the source takes only a mapping
 */
public record ItemSourceDescription(SelfDescription self, String shorthand) {

    public ItemSourceDescription {
        Objects.requireNonNull(self, "self");
        if (shorthand != null && shorthand.isBlank()) {
            throw new IllegalArgumentException("a shorthand is a non-blank parameter name or null");
        }
    }

    public static ItemSourceDescription of(SelfDescription self, String shorthand) {
        return new ItemSourceDescription(self, shorthand);
    }
}
