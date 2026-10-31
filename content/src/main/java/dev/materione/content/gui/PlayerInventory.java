package dev.materione.content.gui;

import java.util.Locale;

/**
 * The GUI's {@code player_inventory:} setting (logic.md §6.5, §25): whether the viewer's own inventory
 * stays usable while the GUI is open. {@link #FREE} is the default and today's behaviour (gui.md §5);
 * {@link #LOCKED} cancels every click in the viewer's inventory and fires {@code gui.click_inventory}.
 */
public enum PlayerInventory {

    FREE,
    LOCKED;

    /** The value an author writes. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether an authored value is one of the two settings (absent counts as {@link #FREE}). */
    public static boolean valid(Object authored) {
        return authored == null || authored instanceof String text && parse(text).isPresent();
    }

    /** The setting an authored value names; absent or invalid reads as {@link #FREE}. */
    public static PlayerInventory of(Object authored) {
        if (authored instanceof String text) {
            return parse(text).orElse(FREE);
        }
        return FREE;
    }

    private static java.util.Optional<PlayerInventory> parse(String text) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        for (PlayerInventory value : values()) {
            if (value.id().equals(lower)) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }
}
