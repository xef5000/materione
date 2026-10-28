package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import java.util.List;

/**
 * The runtime-facing model of one compiled GUI (gui.md §4, §5): what a container screen
 * needs to open, and nothing the pack needs. The background is inside {@link #title()}
 * as a glyph, so the runtime sends the title and the slot table and draws no raster itself.
 *
 * @param id the GUI resource
 * @param rows chest rows, 1 to 6
 * @param title the container title as a MiniMessage string: the background glyph, shift
 *     tags and offset-font text runs, in draw order
 * @param accessibleTitle the GUI's plain title, for narration and title-less clients
 * @param slots the item in each slot the GUI fills, sorted by slot
 */
public record CompiledGui(NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots) {

    /** The canvas width of every chest GUI (vanilla container width). */
    public static final int WIDTH = 176;

    public CompiledGui {
        slots = List.copyOf(slots);
    }

    /** The canvas height of a chest with {@code rows} rows: {@code 114 + rows·18}. */
    public static int height(int rows) {
        return 114 + rows * 18;
    }

    /**
     * One filled slot.
     *
     * @param slot the chest slot, 0 at the top-left, row by row
     * @param item the Materione item or vanilla material, resolved at apply time
     * @param amount how many, 1 to 64
     */
    public record Slot(int slot, NamespacedId item, int amount) {}
}
