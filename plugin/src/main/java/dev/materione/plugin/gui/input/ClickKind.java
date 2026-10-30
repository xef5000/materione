package dev.materione.plugin.gui.input;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The click kinds a {@code gui.click} rule filters on (logic.md §6.2): the union of both incumbents'
 * clicks and everything Bukkit reports for a container. The ids are what an author writes in
 * {@code click:} and what a rule reads as {@code click.type}.
 */
public enum ClickKind {

    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
    DOUBLE,
    DROP,
    CTRL_DROP,
    /** A hotbar key (1 to 9) pressed over the slot; the key is {@code click.key}. */
    NUMBER_KEY,
    SWAP_OFFHAND;

    /** The id an author writes. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Every id, in declaration order: the {@code click:} filter's allowed values. */
    public static List<String> ids() {
        return java.util.Arrays.stream(values()).map(ClickKind::id).toList();
    }

    /** The kind with this id. */
    public static Optional<ClickKind> of(String id) {
        for (ClickKind kind : values()) {
            if (kind.id().equals(id)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
