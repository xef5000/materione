package dev.materione.plugin.gui.browse;

import dev.materione.api.NamespacedId;
import java.util.Locale;

/**
 * One row of the in-game content browser (M6, {@code /materione browse}): a live item, a custom block or a furniture,
 * as the browser files and hands it out.
 *
 * @param kind what the row is: an item, a block or a furniture
 * @param id the row's own id ({@code shop:ruby}, {@code shop:amethyst_lamp})
 * @param name the display name, plain text (formatting tags removed), used for titles and search
 * @param category the group the row is filed under: the authored {@code category:}, else the pack (its namespace)
 * @param item the live item handed out for this row; for a block or a furniture, its placement item. Null when the
 *     block or furniture has none (it is listed, and clicking it says how to place it)
 */
public record BrowseEntry(Kind kind, NamespacedId id, String name, String category, NamespacedId item) {

    /** What a browser row stands for. */
    public enum Kind { ITEM, BLOCK, FURNITURE }

    public BrowseEntry {
        if (kind == null || id == null || name == null || category == null) {
            throw new IllegalArgumentException("a browser entry needs a kind, an id, a name and a category");
        }
    }

    /** The lower-cased text a search matches against: the id and the display name. */
    public String searchKey() {
        return (id + " " + name).toLowerCase(Locale.ROOT);
    }

    /** The category's grouping key: case-insensitive, so {@code Tools} and {@code tools} are one group. */
    public String categoryKey() {
        return categoryKey(category);
    }

    /** The grouping key of a category label. */
    public static String categoryKey(String label) {
        return label.strip().toLowerCase(Locale.ROOT);
    }
}
