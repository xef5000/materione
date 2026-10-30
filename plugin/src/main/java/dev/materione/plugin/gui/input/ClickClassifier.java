package dev.materione.plugin.gui.input;

import java.util.Optional;
import org.bukkit.event.inventory.ClickType;

/**
 * Turns what Bukkit reports for a click into the click kinds rules filter on (logic.md §6.2). A pure
 * function of the {@link ClickType} (and the hotbar button for a number-key click), so the whole table
 * is unit-tested without a server and an adapter only has to call it.
 *
 * <p>Two Bukkit values are not clicks on a slot: the window-border clicks are the player clicking
 * outside the window ({@link #outside}), and {@code CREATIVE} and {@code UNKNOWN} have no meaning for
 * a menu, so they classify to nothing and are cancelled without running a rule.
 */
public final class ClickClassifier {

    private ClickClassifier() {}

    /**
     * One classified click.
     *
     * @param kind what kind of click it was
     * @param key the hotbar key pressed, 1 to 9, for {@link ClickKind#NUMBER_KEY}; 0 for every other kind
     */
    public record Click(ClickKind kind, int key) {}

    /**
     * Classifies a click on a slot of the window.
     *
     * @param hotbarButton Bukkit's {@code getHotbarButton()}: 0 to 8 for a number-key click, -1 otherwise
     * @return the click, or empty for a kind a menu does not handle (creative clone, unknown)
     */
    public static Optional<Click> classify(ClickType type, int hotbarButton) {
        return switch (type) {
            case LEFT -> kind(ClickKind.LEFT);
            case RIGHT -> kind(ClickKind.RIGHT);
            case SHIFT_LEFT -> kind(ClickKind.SHIFT_LEFT);
            case SHIFT_RIGHT -> kind(ClickKind.SHIFT_RIGHT);
            case MIDDLE -> kind(ClickKind.MIDDLE);
            case DOUBLE_CLICK -> kind(ClickKind.DOUBLE);
            case DROP -> kind(ClickKind.DROP);
            case CONTROL_DROP -> kind(ClickKind.CTRL_DROP);
            case SWAP_OFFHAND -> kind(ClickKind.SWAP_OFFHAND);
            case NUMBER_KEY -> hotbarButton >= 0 && hotbarButton <= 8
                    ? Optional.of(new Click(ClickKind.NUMBER_KEY, hotbarButton + 1))
                    : Optional.empty();
            case WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT, CREATIVE, UNKNOWN -> Optional.empty();
        };
    }

    /**
     * Classifies a click outside the window. Only a left or right click there means anything
     * ({@code gui.click_outside} filters on {@code left} and {@code right}).
     */
    public static Optional<ClickKind> outside(ClickType type) {
        return switch (type) {
            case LEFT, WINDOW_BORDER_LEFT -> Optional.of(ClickKind.LEFT);
            case RIGHT, WINDOW_BORDER_RIGHT -> Optional.of(ClickKind.RIGHT);
            default -> Optional.empty();
        };
    }

    private static Optional<Click> kind(ClickKind kind) {
        return Optional.of(new Click(kind, 0));
    }
}
