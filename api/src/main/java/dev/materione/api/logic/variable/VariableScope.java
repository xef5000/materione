package dev.materione.api.logic.variable;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a declared variable lives (logic.md §3.1, §38). {@code local} and {@code session} need no
 * declaration and are not in this enum: they are run-scoped and GUI-session-scoped data.
 */
public enum VariableScope {
    /** Permanent, per player: the player's persistent data container. */
    PLAYER,
    /** Permanent, server-wide: the server's variable store. */
    SERVER,
    /**
     * Held by the item stack a run is about, in the stack's own persistent data (logic.md section 38). A stack
     * whose state differs does not stack with another.
     */
    ITEM;

    /** The authored spelling ({@code player}, {@code server}, {@code item}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The scope an authored word names, case-insensitively. */
    public static Optional<VariableScope> of(String word) {
        if (word == null) {
            return Optional.empty();
        }
        for (VariableScope scope : values()) {
            if (scope.id().equalsIgnoreCase(word.trim())) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
