package dev.materione.api.logic.variable;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a declared variable lives (logic.md §3.1, §38, views.md §3.4). {@code local} needs no declaration and is not in this
 * enum: it is run-scoped data.
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
    ITEM,
    /**
     * Held by one open view instance: private to one viewer of one opened view, kept by {@code view.back}, gone when the
     * view closes (views.md section 3.4). Its store is keyed by the view instance, not by a player.
     */
    SESSION;

    /** The authored spelling ({@code player}, {@code server}, {@code item}, {@code session}). */
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
